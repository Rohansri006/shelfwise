package com.shelfwise.desktop.ui;

import com.shelfwise.core.AppInfo;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Label;

/** Controller for main-view.fxml. Placeholder until the real screens are built. */
public class MainController {

    private static final String DARK = "/com/shelfwise/desktop/css/dark.css";
    private static final String LIGHT = "/com/shelfwise/desktop/css/light.css";

    @FXML
    private Label statusLabel;

    private boolean darkMode = true;

    /** Called automatically by FXMLLoader after the @FXML fields are injected. */
    @FXML
    private void initialize() {
        statusLabel.setText(AppInfo.getDisplayName() + " is running. Core module linked.");
    }

    /** Swaps between dark and light stylesheets. */
    @FXML
    private void onToggleTheme() {
        Scene scene = statusLabel.getScene();
        scene.getStylesheets().clear();
        darkMode = !darkMode;
        String css = darkMode ? DARK : LIGHT;
        scene.getStylesheets().add(getClass().getResource(css).toExternalForm());
    }
}
