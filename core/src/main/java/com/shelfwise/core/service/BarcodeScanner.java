package com.shelfwise.core.service;

import com.shelfwise.core.exception.ShelfWiseException;

/**
 * A device that reads ISBN barcodes.
 * <p>
 * The bulk-scan screen uses only this interface. On the desktop the
 * implementation drives a webcam; on Android it would drive the phone
 * camera. Keeping the screen ignorant of which is in use also means a test
 * can supply a fake scanner that returns fixed barcodes.
 * <p>
 * <strong>Threading.</strong> {@link #start()} and
 * {@link #readNextBarcode()} block - opening a camera can take seconds while
 * the operating system asks for permission. Calling either on the JavaFX
 * thread freezes the window, so they belong on a background thread, with
 * results passed back through {@code Platform.runLater}.
 */
public interface BarcodeScanner {

    /**
     * Whether a usable device is present, checked before offering scanning
     * at all.
     *
     * @return true if scanning can start
     */
    boolean isAvailable();

    /**
     * Opens the device. Blocks until it is ready.
     *
     * @throws ShelfWiseException if the device cannot be opened
     */
    void start() throws ShelfWiseException;

    /**
     * Waits for the next barcode. Blocks until one is read or the scanner
     * is stopped.
     *
     * @return the barcode's digits, or null once {@link #stop()} has been
     *         called
     * @throws ShelfWiseException if reading fails
     */
    String readNextBarcode() throws ShelfWiseException;

    /**
     * Releases the device. Safe to call more than once, and it makes a
     * waiting {@link #readNextBarcode()} return null.
     */
    void stop();
}
