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
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Throwaway prototype proving that barcode scanning works on this machine.
 * <p>
 * This class is deliberately <em>not</em> part of the real application. Its only
 * job is to answer one question before the feature is designed around it: can a
 * Java 21 JavaFX app on an Apple Silicon Mac open the webcam and decode a book
 * barcode? If the answer is no, bulk scanning falls back to typed ISBN entry.
 * <p>
 * Run it with:
 * <pre>
 * mvn -f desktop/pom.xml javafx:run \
 *     -Djavafx.mainClass=com.shelfwise.desktop.prototype.ScanPrototypeApp
 * </pre>
 * macOS will ask for camera permission the first time. If no prompt appears and
 * the preview stays black, grant camera access manually to the program that
 * launched it (Terminal, or IntelliJ) in System Settings &gt; Privacy &amp;
 * Security &gt; Camera, then run it again.
 */
public class ScanPrototypeApp extends Application {

    /** Barcode formats worth looking for. Book barcodes are EAN-13; the other
     *  two are included so a mis-scan of a non-book item still reports something. */
    private static final List<BarcodeFormat> BOOK_FORMATS = Arrays.asList(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A);

    /** The open camera, or null if none could be opened. */
    private Webcam webcam;

    /** Set to false to ask the background thread to finish. Marked volatile
     *  because it is written by the UI thread and read by the scan thread. */
    private volatile boolean running = true;

    /** Live camera frames. */
    private final ImageView preview = new ImageView();

    /** Status line: what the prototype is currently doing. */
    private final Label statusLabel = new Label("Starting camera...");

    /** Every distinct barcode seen so far, newest last. */
    private final ListView<String> scanList = new ListView<>();

    /** Codes already reported, so holding a book still only logs it once. */
    private final List<String> alreadySeen = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        preview.setFitWidth(640);
        preview.setPreserveRatio(true);
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

        startCamera();
    }

    /**
     * Registers the native driver and opens the default camera.
     * <p>
     * The driver must be registered <em>before</em> any other Webcam call,
     * because the library caches its device list on first use. The sarxos
     * built-in driver is the one that hangs on modern macOS; this native
     * driver talks to AVFoundation and ships arm64 binaries.
     */
    private void startCamera() {
        try {
            Webcam.setDriver(new NativeDriver());
            webcam = Webcam.getDefault();

            if (webcam == null) {
                statusLabel.setText("FAILED: no camera found.");
                return;
            }

            // Prefer a mid-range resolution: large frames decode no better and
            // slow the loop down. Fall back to whatever the device offers.
            Dimension[] sizes = webcam.getViewSizes();
            Dimension chosen = pickViewSize(sizes);
            if (chosen != null) {
                webcam.setViewSize(chosen);
            }

            webcam.open();
            statusLabel.setText("Camera open (" + webcam.getName()
                    + "). Hold a barcode in view.");

            // The capture loop must not run on the JavaFX thread, or the window
            // would freeze. A daemon thread dies automatically with the app.
            Thread scanThread = new Thread(new ScanLoop(), "scan-loop");
            scanThread.setDaemon(true);
            scanThread.start();

        } catch (Exception e) {
            // Deliberately broad: this is a prototype whose purpose is to find
            // out *how* it fails on this hardware, so nothing is swallowed.
            statusLabel.setText("FAILED: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            e.printStackTrace();
        }
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

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, BOOK_FORMATS);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        try {
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return result.getText();
        } catch (NotFoundException e) {
            // Normal: most frames have no barcode in them. Not an error.
            return null;
        }
    }

    /**
     * Reports a newly seen barcode on the JavaFX thread.
     * <p>
     * Called from the scan thread, so every UI change is wrapped in
     * {@code Platform.runLater}. Touching JavaFX controls from another thread
     * throws, and that mistake is the usual cause of a camera app freezing.
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
                System.out.println("SCANNED: " + code + " (" + kind + ")");
            }
        });
    }

    /** Closes the camera when the window closes, so the device is released. */
    @Override
    public void stop() {
        running = false;
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
        }
    }

    /**
     * The capture loop: grab a frame, show it, try to decode it, repeat.
     * Written as a named class rather than a lambda so every step is visible.
     */
    private class ScanLoop implements Runnable {

        @Override
        public void run() {
            while (running) {
                BufferedImage frame = webcam.getImage();

                if (frame == null) {
                    // The driver can return null briefly while starting up.
                    sleepQuietly(50);
                    continue;
                }

                // Show the frame. SwingFXUtils converts the AWT image the
                // webcam library produces into the image type JavaFX draws.
                final BufferedImage frameForUi = frame;
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        preview.setImage(SwingFXUtils.toFXImage(frameForUi, null));
                    }
                });

                String code = decodeBarcode(frame);
                if (code != null) {
                    reportCode(code);
                }

                // Roughly 10 decode attempts a second: responsive to the user,
                // while leaving the CPU alone.
                sleepQuietly(100);
            }
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
