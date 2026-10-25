package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ScannerException;
import com.shelfwise.core.service.BarcodeScanner;
import javafx.application.Platform;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * Runs a {@link WebcamScanner} on a background thread for a screen (E59):
 * the Add page opens it when scanning starts and stops it when the user
 * leaves.
 * <p>
 * <strong>Threading.</strong> The scanner's calls block, and opening a
 * camera can block for seconds while macOS asks for permission, so they all
 * run on one background thread. Doing them on the JavaFX thread would
 * freeze the window. The {@link Listener} is always called on the JavaFX
 * thread, through {@code Platform.runLater}, so screen code never has to
 * think about threads.
 * <p>
 * The reading loop talks to the scanner only through the
 * {@link BarcodeScanner} interface: {@code start}, {@code readNextBarcode},
 * {@code stop}. Any implementation would work here, such as a fake one
 * returning fixed barcodes.
 */
public class CameraFeed {

    /** What the camera reports. Always called on the JavaFX thread. */
    public interface Listener {

        /** The cameras found, by name, in device order. */
        void devices(List<String> names);

        /** The camera is open and frames are arriving. */
        void live(String name);

        /** A barcode was read. Repeats are reported; the screen decides what a repeat means. */
        void code(String code);

        /** No camera, permission refused, or the driver failed. */
        void failed(String message);
    }

    /** Where frames are drawn. */
    private final ImageView preview;

    /**
     * The thread currently allowed to report. A loop checks it is still this
     * thread: after stop() then start() (changing camera), the old thread
     * would otherwise keep reporting alongside the new one. {@code volatile}
     * because the JavaFX thread writes it and the camera threads read it.
     */
    private volatile Thread worker;

    /** The scanner in use, so stop() can release it; null when stopped. */
    private volatile BarcodeScanner scanner;

    /** @param preview where the camera's picture is drawn */
    public CameraFeed(ImageView preview) {
        this.preview = preview;
    }

    /**
     * Opens a camera on a background thread and starts reading barcodes.
     * Any camera already running is stopped first.
     *
     * @param deviceIndex which camera, in the order {@link Listener#devices} gave
     * @param listener    told what happens
     */
    public void start(int deviceIndex, final Listener listener) {
        stop();
        final WebcamScanner camera = new WebcamScanner(deviceIndex);
        camera.setPreview(preview);
        scanner = camera;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                feed(camera, listener);
            }
        }, "camera-feed");
        // A daemon thread does not keep the program alive after the window closes.
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Releases the camera. A barcode still on its way to the screen is
     * dropped, because its thread is no longer the current worker.
     */
    public void stop() {
        worker = null;
        BarcodeScanner current = scanner;
        scanner = null;
        if (current != null) {
            current.stop();
        }
    }

    /** Runs on the camera thread from start to finish. */
    private void feed(WebcamScanner camera, final Listener listener) {
        Thread me = Thread.currentThread();
        // From here on the scanner is used through its interface only.
        BarcodeScanner device = camera;
        try {
            final List<String> names = WebcamScanner.cameraNames();
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    listener.devices(names);
                }
            });
            device.start();
            final String name = camera.getDeviceName();
            if (worker == me) {
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        listener.live(name);
                    }
                });
            }
            String code = device.readNextBarcode();
            // null means stopped; a newer worker also means this one is finished.
            while (code != null && worker == me) {
                report(me, listener, code);
                code = device.readNextBarcode();
            }
        } catch (final ScannerException e) {
            if (worker == me) {
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        listener.failed(e.getMessage());
                    }
                });
            }
        } finally {
            device.stop();
        }
    }

    /**
     * Passes one barcode to the screen, checking again on the JavaFX thread
     * that this feed was not stopped while the message was queued.
     */
    private void report(final Thread me, final Listener listener, final String code) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                if (worker == me) {
                    listener.code(code);
                }
            }
        });
    }
}
