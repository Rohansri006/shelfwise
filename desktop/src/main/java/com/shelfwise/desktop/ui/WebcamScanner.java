package com.shelfwise.desktop.ui;

import com.github.eduramiba.webcamcapture.drivers.NativeDriver;
import com.github.sarxos.webcam.Webcam;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.shelfwise.core.exception.ScannerException;
import com.shelfwise.core.service.BarcodeScanner;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The desktop's {@link BarcodeScanner}: a webcam read with ZXing (E155).
 * <p>
 * It lives in desktop, not core, because the webcam library is desktop-only:
 * the future Android app implements the same interface with the phone camera,
 * and core stays usable there.
 * <p>
 * The camera is driven by sarxos webcam-capture with the eduramiba native
 * driver, the combination measured at 43 fps on Apple Silicon (CLAUDE.md §6).
 * The tuning from that prototype is kept: every frame is shown, but only one
 * every 120 ms is decoded, and the reader and preview image are reused.
 * <p>
 * <strong>Threading.</strong> Like the interface says, {@link #start()} and
 * {@link #readNextBarcode()} block, so they run on a background thread
 * ({@link CameraFeed} provides one). {@link #stop()} may be called from any
 * thread, including the JavaFX one.
 */
public class WebcamScanner implements BarcodeScanner {

    /** The barcodes printed on books: EAN-13 (ISBN-13), plus EAN-8 and UPC-A. */
    private static final List<BarcodeFormat> BOOK_FORMATS = Arrays.asList(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A);

    /** A short rest between frames, so the loop does not spin a CPU core flat out. */
    private static final long GRAB_PAUSE_MS = 10;

    /** Decoding is the slow part; showing every frame but decoding fewer keeps the preview smooth. */
    private static final long DECODE_INTERVAL_MS = 120;

    /**
     * The driver must be registered once, before any other webcam call: the
     * library caches its device list on first use. Static because it is per
     * program, not per scanner.
     */
    private static boolean driverSet = false;

    /** Which camera, in the order {@link #cameraNames()} lists them. */
    private final int deviceIndex;

    /** Not thread-safe, so one per scanner, used only by the reading thread. */
    private final MultiFormatReader reader = new MultiFormatReader();

    /**
     * Guards the camera between the reading thread (grabbing a frame) and
     * whichever thread calls {@link #stop()} (closing it), so the device is
     * never closed in the middle of a grab.
     */
    private final Object lock = new Object();

    /**
     * Written by {@link #stop()} on one thread, read by the reading loop on
     * another. {@code volatile} makes the write visible to the other thread
     * at once; without it the loop could keep running on a stale value.
     */
    private volatile boolean running = false;

    private Webcam webcam;
    private String deviceName;
    private long lastDecodeAt = 0;

    /** Where frames are drawn, or null for none. */
    private ImageView preview;
    private WritableImage fxFrame;

    /** True while a frame waits to be drawn; newer frames are dropped rather than queued. */
    private final AtomicBoolean uiUpdatePending = new AtomicBoolean(false);

    /**
     * Creates a scanner for one camera. Nothing is opened until {@link #start()}.
     *
     * @param deviceIndex which camera, in the order {@link #cameraNames()} gives
     */
    public WebcamScanner(int deviceIndex) {
        this.deviceIndex = deviceIndex;
        // Hints tell ZXing which formats to try, so it does not waste time on QR codes.
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, BOOK_FORMATS);
        reader.setHints(hints);
    }

    /**
     * The cameras present, by name, in device order. Blocks briefly while
     * the system lists its devices, so call it off the JavaFX thread.
     *
     * @return the names; empty if there is no camera
     * @throws ScannerException if the camera library cannot run at all
     */
    public static List<String> cameraNames() throws ScannerException {
        List<String> names = new ArrayList<>();
        List<Webcam> all = webcams();
        for (int i = 0; i < all.size(); i++) {
            names.add(all.get(i).getName());
        }
        return names;
    }

    /**
     * Registers the driver on first use, then lists the cameras.
     * {@code synchronized} on the class: two scanners starting at once must
     * not both register it.
     */
    private static synchronized List<Webcam> webcams() throws ScannerException {
        try {
            if (!driverSet) {
                Webcam.setDriver(new NativeDriver());
                driverSet = true;
            }
            return Webcam.getWebcams();
        } catch (Throwable t) {
            // Throwable, not Exception: a missing native library arrives as an
            // UnsatisfiedLinkError, which is an Error.
            throw new ScannerException(null, t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * @param preview where to draw the camera's picture, or null for none.
     *                Display only: frames are always decoded as they come.
     */
    public void setPreview(ImageView preview) {
        this.preview = preview;
    }

    /** @return the open camera's name, or null before {@link #start()} */
    public String getDeviceName() {
        return deviceName;
    }

    /** True if at least one camera is present. Blocks briefly; see {@link #cameraNames()}. */
    @Override
    public boolean isAvailable() {
        try {
            return !cameraNames().isEmpty();
        } catch (ScannerException e) {
            return false;
        }
    }

    /**
     * Opens the camera. May block for seconds on macOS the first time, while
     * the system asks for camera permission.
     */
    @Override
    public void start() throws ScannerException {
        running = true;
        List<Webcam> all = webcams();
        if (all.isEmpty()) {
            running = false;
            throw new ScannerException(null, "no camera found");
        }
        // An index past the end (a camera unplugged since) falls back to the last one.
        Webcam chosen = all.get(Math.min(deviceIndex, all.size() - 1));
        deviceName = chosen.getName();
        boolean opened;
        try {
            Dimension size = largestUpTo1280(chosen.getViewSizes());
            if (size != null) {
                chosen.setViewSize(size);
            }
            // Not inside the lock: open() can take seconds, and stop() must not wait for it.
            opened = chosen.open();
        } catch (Throwable t) {
            running = false;
            throw new ScannerException(deviceName, t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
        if (!opened) {
            running = false;
            throw new ScannerException(deviceName,
                    "it would not open. Check System Settings > Privacy & Security > Camera");
        }
        synchronized (lock) {
            webcam = chosen;
            // stop() was called while open() was blocking: close at once.
            if (!running) {
                closeCamera();
            }
        }
    }

    /**
     * Shows frames until one holds a barcode. Repeats are returned each time
     * they are read; the screen decides what a repeat means.
     *
     * @return the barcode's digits, or null once {@link #stop()} has been called
     */
    @Override
    public String readNextBarcode() throws ScannerException {
        while (running) {
            BufferedImage frame;
            synchronized (lock) {
                if (!running || webcam == null) {
                    return null;
                }
                try {
                    frame = webcam.getImage();
                } catch (RuntimeException e) {
                    throw new ScannerException(deviceName, "it stopped sending pictures", e);
                }
            }
            if (frame == null) {
                // No new frame yet, which is normal right after opening.
                pause(50);
                continue;
            }
            show(frame);
            long now = System.currentTimeMillis();
            if (now - lastDecodeAt >= DECODE_INTERVAL_MS) {
                lastDecodeAt = now;
                String code = decode(frame);
                if (code != null) {
                    return code;
                }
            }
            pause(GRAB_PAUSE_MS);
        }
        return null;
    }

    /** Closes the camera. Safe to call more than once, and before {@link #start()}. */
    @Override
    public void stop() {
        running = false;
        synchronized (lock) {
            closeCamera();
        }
    }

    /** Called with the lock held. */
    private void closeCamera() {
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
        }
        webcam = null;
    }

    /**
     * Reads a barcode from one picture. Package-private so a test can give
     * it a generated barcode without a camera.
     *
     * @param frame a camera frame
     * @return the digits, or null if the picture holds no readable barcode
     */
    String decode(BufferedImage frame) {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(frame)));
        try {
            // decodeWithState reuses the hints set once in the constructor,
            // instead of setting them up again for every frame.
            Result result = reader.decodeWithState(bitmap);
            return result.getText();
        } catch (NotFoundException e) {
            // Most frames hold no barcode: normal, not an error.
            return null;
        }
    }

    /**
     * Shows a frame unless the previous one is still waiting to be drawn.
     * Dropping frames rather than queueing them keeps the preview live: a
     * queue would fall further and further behind the camera.
     */
    private void show(final BufferedImage frame) {
        final ImageView target = preview;
        if (target == null || !uiUpdatePending.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                try {
                    // Reuses the last image's memory when the size is the same.
                    fxFrame = SwingFXUtils.toFXImage(frame, fxFrame);
                    target.setImage(fxFrame);
                } finally {
                    uiUpdatePending.set(false);
                }
            }
        });
    }

    /** @return the widest size up to 1280 px: enough to read a barcode, light enough for 40+ fps */
    private static Dimension largestUpTo1280(Dimension[] sizes) {
        Dimension best = null;
        for (int i = 0; sizes != null && i < sizes.length; i++) {
            if (sizes[i].width <= 1280 && (best == null || sizes[i].width > best.width)) {
                best = sizes[i];
            }
        }
        return best;
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Keep the interrupt visible to the caller, and end the loop.
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
