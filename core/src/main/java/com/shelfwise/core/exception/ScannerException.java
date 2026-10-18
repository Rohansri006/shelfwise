package com.shelfwise.core.exception;

/**
 * Thrown when a barcode scanner cannot be opened or stops working.
 * <p>
 * Like {@link MetadataFetchException}, this is a failure the user can do
 * something about: grant camera permission, close another app that holds
 * the camera, or fall back to typing ISBNs into the paste box. So it is
 * checked, and the bulk-scan screen must decide what to offer.
 * <p>
 * It exists so that {@code BarcodeScanner} can declare exactly what it
 * throws. Declaring the base {@link ShelfWiseException} would force callers
 * to handle failures a scanner can never produce, such as a duplicate ISBN.
 * <p>
 * The device name is carried so the message can say which camera failed;
 * a laptop with an external webcam has two.
 */
public class ScannerException extends ShelfWiseException {

    /** Which device failed, e.g. "FaceTime HD Camera"; null if none found. */
    private final String deviceName;

    /** Why it failed, in a few words, e.g. "camera permission denied". */
    private final String reason;

    /**
     * Creates an exception for a failure the scanner detected itself, such
     * as finding no camera at all.
     *
     * @param deviceName which device failed, or null if there is none
     * @param reason     why it failed
     */
    public ScannerException(String deviceName, String reason) {
        super(describe(deviceName) + ": " + reason + ".");
        this.deviceName = deviceName;
        this.reason = reason;
    }

    /**
     * Creates an exception wrapping a lower-level failure from the camera
     * library, keeping its stack trace for debugging.
     *
     * @param deviceName which device failed, or null if there is none
     * @param reason     why it failed
     * @param cause      the underlying exception
     */
    public ScannerException(String deviceName, String reason, Throwable cause) {
        super(describe(deviceName) + ": " + reason + ".", cause);
        this.deviceName = deviceName;
        this.reason = reason;
    }

    /**
     * Static because it runs inside the {@code super(...)} call, before the
     * object's own fields exist.
     *
     * @param deviceName the device, or null
     * @return the start of the message, naming the device if known
     */
    private static String describe(String deviceName) {
        if (deviceName == null) {
            return "The scanner could not be used";
        }
        return "The camera \"" + deviceName + "\" could not be used";
    }

    /** @return which device failed, or null if no device was found */
    public String getDeviceName() {
        return deviceName;
    }

    /** @return the reason, e.g. "camera permission denied" */
    public String getReason() {
        return reason;
    }
}
