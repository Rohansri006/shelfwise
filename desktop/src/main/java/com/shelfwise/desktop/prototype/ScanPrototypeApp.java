package com.shelfwise.desktop.prototype;

import com.github.eduramiba.webcamcapture.drivers.NativeDriver;
import com.github.sarxos.webcam.Webcam;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Throwaway prototype proving that barcode scanning works on this machine.
 * <p>
 * Not part of the real application. Its only job is to answer one question
 * before the feature is designed around it: can a Java 21 JavaFX app on an
 * Apple Silicon Mac open the webcam and decode a book barcode? If not, bulk
 * scanning falls back to typed ISBN entry.
 * <p>
 * <strong>Threading rule, learned the hard way:</strong> every camera call
 * here is blocking and native - {@code setDriver}, {@code getDefault},
 * {@code getViewSizes} and above all {@code open()}, which can sit for
 * seconds while macOS resolves the camera permission grant. Doing any of
 * that on the JavaFX Application Thread freezes the window to a white
 * rectangle with a spinning cursor. So <em>all</em> camera work happens on a
 * background thread, and the only things that touch the UI are wrapped in
 * {@link Platform#runLater}.
 * <p>
 * Run it with:
 * <pre>
 * mvn -f desktop/pom.xml javafx:run \
 *     -Dapp.mainClass=com.shelfwise.desktop.prototype.ScanPrototypeApp
 * </pre>
 * Optionally add {@code -Dscan.log=/some/path.log} to get a timestamped
 * trace of every startup step, which is how a hang is located.
 */
public class ScanPrototypeApp extends Application {

    /** Barcode formats worth looking for. Book barcodes are EAN-13; the other
     *  two are included so a mis-scan of a non-book item still reports something. */
    private static final List<BarcodeFormat> BOOK_FORMATS = Arrays.asList(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A);

    /** Pause between frame grabs. Small, so the preview stays smooth. */
    private static final long GRAB_PAUSE_MS = 10;

    /** How often to attempt a decode. Decoding is far more expensive than
     *  displaying, so it runs on its own slower cadence - about 8 times a
     *  second, which is still faster than anyone can present a book. */
    private static final long DECODE_INTERVAL_MS = 120;

    /** One reader reused for every decode. Constructing a MultiFormatReader
     *  per frame was a needless allocation on a hot path. */
    private final MultiFormatReader reader = new MultiFormatReader();

    /** Reused across frames by SwingFXUtils, which only allocates a new one
     *  when the dimensions change. Avoids a full-size image allocation per
     *  frame, which was causing visible garbage-collection stutter. */
    private WritableImage fxFrame;

    /** True while a preview update is already queued on the FX thread.
     *  Without this guard the camera thread can post frames faster than
     *  JavaFX renders them, building a backlog that shows up as lag. */
    private final AtomicBoolean uiUpdatePending = new AtomicBoolean(false);

    /** The open camera, or null until the background thread has opened one. */
    private Webcam webcam;

    /** Set to false to ask the background thread to finish. Volatile because
     *  it is written by the UI thread and read by the camera thread. */
    private volatile boolean running = true;

    /** Live camera frames. */
    private final ImageView preview = new ImageView();

    /** Status line: what the prototype is currently doing. */
    private final Label statusLabel = new Label("Starting camera on a background thread...");

    /** Every distinct barcode seen so far, newest last. */
    private final ListView<String> scanList = new ListView<>();

    /** Codes already reported, so holding a book still only logs it once. */
    private final List<String> alreadySeen = new ArrayList<>();

    /** Optional file trace, or null when -Dscan.log was not supplied. */
    private PrintWriter logWriter;

    @Override
    public void start(Stage stage) {
        openLogFile();

        preview.setFitWidth(640);
        preview.setPreserveRatio(true);

        // macOS mirrors the front-facing camera by default, the way Photo Booth
        // does, which shows barcode digits reversed. Negating the horizontal
        // scale flips the displayed node back.
        //
        // This affects the DISPLAY ONLY: the BufferedImage handed to ZXing is
        // the untouched camera frame, so decoding is unchanged. Note the
        // trade-off - with the mirror removed, moving a book to your right now
        // moves it left on screen.
        preview.setScaleX(-1);

        scanList.setPrefHeight(140);

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getChildren().add(new Label("Barcode scan prototype"));
        root.getChildren().add(preview);
        root.getChildren().add(statusLabel);
        root.getChildren().add(new Label("Scanned codes:"));
        root.getChildren().add(scanList);

        stage.setTitle("ShelfWise - scan prototype");
        stage.setScene(new Scene(root, 700, 720));
        stage.show();

        log("UI shown; starting camera thread");

        // Nothing camera-related runs here. A daemon thread dies with the app.
        Thread cameraThread = new Thread(new CameraWorker(), "camera-worker");
        cameraThread.setDaemon(true);
        cameraThread.start();
    }

    /** Opens the trace file named by -Dscan.log, if one was given. */
    private void openLogFile() {
        String path = System.getProperty("scan.log");
        if (path == null) {
            return;
        }
        try {
            // autoFlush: every line hits the disk immediately, so the trace
            // survives a hang or a kill -9.
            logWriter = new PrintWriter(new FileWriter(path, false), true);
        } catch (IOException e) {
            System.out.println("Could not open scan log: " + e.getMessage());
        }
    }

    /**
     * Writes one timestamped line to stdout and to the trace file.
     * Safe to call from any thread.
     *
     * @param message what happened
     */
    private synchronized void log(String message) {
        String line = LocalTime.now().toString() + "  " + message;
        System.out.println(line);
        if (logWriter != null) {
            logWriter.println(line);
        }
    }

    /**
     * Updates the status label from any thread.
     *
     * @param message text for the status line
     */
    private void setStatus(final String message) {
        log("STATUS: " + message);
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText(message);
            }
        });
    }

    /**
     * Tries to read a barcode out of one frame.
     *
     * @param frame a camera frame
     * @return the barcode digits, or null if this frame contained none
     */
    private String decodeBarcode(BufferedImage frame) {
        // ZXing works on brightness, not colour: the image is wrapped in a
        // luminance source, then turned into black and white by a binarizer.
        LuminanceSource source = new BufferedImageLuminanceSource(frame);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        try {
            // decodeWithState reuses the readers built when the hints were set,
            // instead of rebuilding them on every call.
            Result result = reader.decodeWithState(bitmap);
            return result.getText();
        } catch (NotFoundException e) {
            // Normal: most frames have no barcode in them. Not an error.
            return null;
        }
    }

    /**
     * Configures the shared reader once, before the capture loop starts.
     * <p>
     * TRY_HARDER is deliberately NOT set. It makes ZXing do a far more
     * exhaustive search, which roughly tripled the decode time and was the
     * main reason the preview was slow. A barcode held up to a camera is an
     * easy target, and it is being re-tried eight times a second anyway.
     */
    private void configureReader() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, BOOK_FORMATS);
        reader.setHints(hints);
    }

    /**
     * Reports a newly seen barcode on the JavaFX thread.
     *
     * @param code the barcode digits
     */
    private void reportCode(final String code) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                if (alreadySeen.contains(code)) {
                    return;
                }
                alreadySeen.add(code);

                // A book barcode is an EAN-13 starting 978 or 979.
                String kind;
                if (code.length() == 13 && (code.startsWith("978") || code.startsWith("979"))) {
                    kind = "ISBN-13";
                } else {
                    kind = "not a book barcode";
                }

                scanList.getItems().add(code + "   (" + kind + ")");
                statusLabel.setText("Last scan: " + code + " - " + kind);
                log("SCANNED: " + code + " (" + kind + ")");
            }
        });
    }

    /** Closes the camera when the window closes, so the device is released. */
    @Override
    public void stop() {
        running = false;
        log("stop() called; releasing camera");
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
        }
        if (logWriter != null) {
            logWriter.close();
        }
    }

    /**
     * Opens the camera and then loops: grab a frame, show it, try to decode.
     * <p>
     * Every step is logged before it runs, so if one of them never returns
     * the trace file names the exact call that hung.
     */
    private class CameraWorker implements Runnable {

        @Override
        public void run() {
            try {
                // The driver must be registered before any other Webcam call,
                // because the library caches its device list on first use. The
                // sarxos built-in driver hangs on modern macOS; this native
                // driver talks to AVFoundation and ships arm64 binaries.
                log("step 1: Webcam.setDriver(NativeDriver)");
                Webcam.setDriver(new NativeDriver());

                log("step 2: Webcam.getWebcams() - enumerating devices");
                List<Webcam> all = Webcam.getWebcams();
                log("        found " + all.size() + " device(s)");
                for (int i = 0; i < all.size(); i++) {
                    log("        [" + i + "] " + all.get(i).getName());
                }

                if (all.isEmpty()) {
                    setStatus("FAILED: no camera found.");
                    return;
                }

                webcam = all.get(0);

                log("step 3: getViewSizes()");
                Dimension[] sizes = webcam.getViewSizes();
                Dimension chosen = pickViewSize(sizes);
                if (chosen != null) {
                    log("        choosing " + chosen.width + "x" + chosen.height);
                    webcam.setViewSize(chosen);
                } else {
                    log("        keeping device default size");
                }

                // The call that used to freeze the UI. macOS can block here
                // for seconds while the camera permission grant is resolved.
                log("step 4: webcam.open() - may block on macOS permission");
                boolean opened = webcam.open();
                log("        open() returned " + opened);

                if (!opened) {
                    setStatus("FAILED: camera found but would not open. "
                            + "Check System Settings > Privacy & Security > Camera.");
                    return;
                }

                setStatus("Camera open (" + webcam.getName()
                        + "). Hold a barcode in view.");

                log("step 5: configuring barcode reader");
                configureReader();

                log("step 6: entering capture loop");
                captureLoop();

            } catch (Throwable t) {
                // Deliberately catches Throwable: an UnsatisfiedLinkError from
                // a missing or wrong-architecture native library is exactly the
                // failure this prototype exists to detect, and it is an Error,
                // not an Exception.
                log("EXCEPTION: " + t.getClass().getName() + " - " + t.getMessage());
                setStatus("FAILED: " + t.getClass().getSimpleName() + " - " + t.getMessage());
                t.printStackTrace();
                if (logWriter != null) {
                    t.printStackTrace(logWriter);
                }
            }
        }

        /**
         * Grab, display, decode, repeat until the window closes.
         * <p>
         * Display and decode run at different rates on purpose. Showing a
         * frame is cheap; decoding one is not. Originally both happened on
         * every iteration, so the preview could never run faster than the
         * decoder - which is what made it look slow. Now every frame is
         * displayed and only every ~120ms is a frame decoded.
         */
        private void captureLoop() {
            int frames = 0;
            int decodes = 0;
            int nulls = 0;
            long lastDecodeAt = 0;
            long startedAt = System.currentTimeMillis();

            while (running) {
                BufferedImage frame = webcam.getImage();

                if (frame == null) {
                    // The driver can return null briefly while starting up.
                    nulls++;
                    if (nulls == 20) {
                        setStatus("Camera open but returning no frames - "
                                + "the preview will stay blank.");
                    }
                    sleepQuietly(50);
                    continue;
                }

                frames++;
                if (frames == 1) {
                    log("first frame received: " + frame.getWidth()
                            + "x" + frame.getHeight());
                    setStatus("Camera live. Hold a barcode in view.");
                }

                showFrame(frame);

                // Decode on its own slower cadence.
                long now = System.currentTimeMillis();
                if (now - lastDecodeAt >= DECODE_INTERVAL_MS) {
                    lastDecodeAt = now;
                    decodes++;
                    String code = decodeBarcode(frame);
                    if (code != null) {
                        reportCode(code);
                    }
                }

                // Report throughput once, so "is it fast enough?" has a number
                // behind it rather than an impression.
                if (frames == 200) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    log("throughput: " + frames + " frames, " + decodes
                            + " decodes in " + elapsed + "ms ("
                            + (frames * 1000L / Math.max(elapsed, 1)) + " fps)");
                }

                sleepQuietly(GRAB_PAUSE_MS);
            }
            log("capture loop ended after " + frames + " frames");
        }

        /**
         * Hands one frame to the UI thread for display, skipping the update if
         * the previous one has not been drawn yet.
         * <p>
         * Dropping a frame is strictly better than queueing it: a backlog of
         * stale frames on the FX thread is what makes a preview feel laggy.
         *
         * @param frame the camera frame to show
         */
        private void showFrame(final BufferedImage frame) {
            if (!uiUpdatePending.compareAndSet(false, true)) {
                return;
            }
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Passing fxFrame lets SwingFXUtils write into the
                        // existing image instead of allocating a new one.
                        fxFrame = SwingFXUtils.toFXImage(frame, fxFrame);
                        preview.setImage(fxFrame);
                    } finally {
                        uiUpdatePending.set(false);
                    }
                }
            });
        }

        /**
         * Chooses the largest offered resolution no wider than 1280 pixels.
         *
         * @param sizes resolutions the camera reports, possibly empty
         * @return the chosen size, or null to keep the camera's default
         */
        private Dimension pickViewSize(Dimension[] sizes) {
            if (sizes == null || sizes.length == 0) {
                return null;
            }
            Dimension best = null;
            for (int i = 0; i < sizes.length; i++) {
                Dimension candidate = sizes[i];
                if (candidate.width <= 1280) {
                    if (best == null || candidate.width > best.width) {
                        best = candidate;
                    }
                }
            }
            return best;
        }

        /**
         * Pauses this thread, treating interruption as a request to stop.
         *
         * @param millis how long to pause
         */
        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                // Restore the flag the exception cleared, then end the loop.
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
