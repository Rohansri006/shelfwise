package com.shelfwise.desktop;

/**
 * Entry point for running from IntelliJ.
 * <p>
 * A main class that directly extends {@code Application} fails with
 * "JavaFX runtime components are missing" when JavaFX is on the classpath
 * instead of the module path. Launching through this plain class avoids that.
 */
public class Launcher {

    public static void main(String[] args) {
        ShelfWiseApp.main(args);
    }
}
