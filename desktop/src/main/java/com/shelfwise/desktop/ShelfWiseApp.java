package com.shelfwise.desktop;

import com.shelfwise.core.AppInfo;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/** JavaFX application: loads the main window from FXML and applies the dark theme. */
public class ShelfWiseApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                ShelfWiseApp.class.getResource("ui/main-view.fxml"));
        Scene scene = new Scene(loader.load(), 900, 600);

        // Dark theme is the default; MainController can swap it for light.
        scene.getStylesheets().add(
                ShelfWiseApp.class.getResource("css/dark.css").toExternalForm());

        stage.setTitle(AppInfo.getDisplayName());
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
