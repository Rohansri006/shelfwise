package com.shelfwise.desktop.prototype;

/**
 * Entry point for running the scan prototype from IntelliJ.
 * <p>
 * Mirrors {@link com.shelfwise.desktop.Launcher}, and exists for the same
 * reason: a main method inside a class that extends {@code Application}
 * fails with "JavaFX runtime components are missing" when JavaFX is on the
 * classpath rather than the module path, which is how IntelliJ launches a
 * plain Maven project. Starting from a class that does <em>not</em> extend
 * {@code Application} sidesteps the check.
 * <p>
 * Right-click this file in IntelliJ and choose Run. No arguments or VM
 * options are needed.
 * <p>
 * The Maven equivalent, which does not need this class because the JavaFX
 * plugin builds a proper module path, is:
 * <pre>
 * mvn -f desktop/pom.xml javafx:run \
 *     -Dapp.mainClass=com.shelfwise.desktop.prototype.ScanPrototypeApp
 * </pre>
 */
public class ScanPrototypeLauncher {

    /**
     * @param args passed straight through to the JavaFX application
     */
    public static void main(String[] args) {
        ScanPrototypeApp.main(args);
    }
}
