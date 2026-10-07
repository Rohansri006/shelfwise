package com.shelfwise.core;

/**
 * Application-wide constants.
 * Used by the desktop module to confirm that core is linked correctly.
 */
public final class AppInfo {

    public static final String NAME = "ShelfWise";
    public static final String VERSION = "1.0-SNAPSHOT";

    /** Private constructor: this class only holds constants and must not be instantiated. */
    private AppInfo() {
    }

    /** @return name and version, e.g. "ShelfWise 1.0-SNAPSHOT" */
    public static String getDisplayName() {
        return NAME + " " + VERSION;
    }
}
