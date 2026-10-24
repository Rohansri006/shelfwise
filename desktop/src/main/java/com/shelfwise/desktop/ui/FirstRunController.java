package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.GuestUser;
import com.shelfwise.core.model.LocalUser;
import com.shelfwise.core.model.User;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The first-run page (E44, D22, E104): shown over the whole window on a
 * fresh install, before any collection exists. Three stacked steps: the
 * folder, Guest or Local user, and the sample books (offered only for an
 * empty folder). The app saves the choices; this page only collects them.
 */
public class FirstRunController {

    /** What first run hands back to the app. */
    public interface Done {

        /**
         * @param user   the Guest or Local user chosen
         * @param folder the folder chosen
         * @param sample true if the sample books were asked for (and offered)
         */
        void finished(User user, Path folder, boolean sample);
    }

    @FXML private Label folderLabel;
    @FXML private ToggleButton guestCard;
    @FXML private ToggleButton localCard;
    @FXML private VBox localFields;
    @FXML private TextField nameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField repeatField;
    @FXML private VBox sampleStep;
    @FXML private CheckBox sampleCheck;
    @FXML private Label errorLabel;

    private Done done;
    private Path folder;

    @FXML
    private void initialize() {
        // The two cards act as one choice, like radio buttons.
        ToggleGroup who = new ToggleGroup();
        guestCard.setToggleGroup(who);
        localCard.setToggleGroup(who);
    }

    /** Starts from the defaults: ~/ShelfWise and Guest. */
    public void open(Done done) {
        this.done = done;
        setFolder(java.nio.file.Paths.get(System.getProperty("user.home"), "ShelfWise"));
        guestCard.setSelected(true);
        showLocalFields(false);
        nameField.clear();
        passwordField.clear();
        repeatField.clear();
        sampleCheck.setSelected(true);
        errorLabel.setText("");
    }

    @FXML
    private void onChangeFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Where should ShelfWise keep your collection?");
        if (Files.isDirectory(folder)) {
            chooser.setInitialDirectory(folder.toFile());
        }
        File chosen = chooser.showDialog(folderLabel.getScene().getWindow());
        if (chosen != null) {
            setFolder(chosen.toPath());
        }
    }

    /** D22: the sample step only shows for a folder with nothing in it. */
    private void setFolder(Path folder) {
        this.folder = folder;
        folderLabel.setText(folder.toString());
        boolean empty = isEmpty(folder);
        sampleStep.setVisible(empty);
        sampleStep.setManaged(empty);
    }

    /** A folder that does not exist yet counts as empty: first run would create it. */
    private static boolean isEmpty(Path folder) {
        if (!Files.isDirectory(folder)) {
            return true;
        }
        // try-with-resources closes the listing even if reading fails.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            return !files.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    @FXML
    private void onGuest() {
        guestCard.setSelected(true);
        showLocalFields(false);
    }

    @FXML
    private void onLocal() {
        localCard.setSelected(true);
        showLocalFields(true);
        nameField.requestFocus();
    }

    private void showLocalFields(boolean show) {
        localFields.setVisible(show);
        localFields.setManaged(show);
        errorLabel.setText("");
    }

    /**
     * Builds the chosen user. Both are {@link User}s, so the app receives
     * one type whichever was picked: the subclass decides later whether a
     * password is asked (runtime polymorphism).
     */
    @FXML
    private void onStart() {
        User user;
        if (localCard.isSelected()) {
            if (!passwordField.getText().equals(repeatField.getText())) {
                errorLabel.setText("The two passwords are different.");
                return;
            }
            try {
                user = new LocalUser(nameField.getText(), passwordField.getText());
            } catch (IllegalArgumentException e) {
                // A blank name or password.
                errorLabel.setText(e.getMessage());
                return;
            }
        } else {
            user = new GuestUser();
        }
        done.finished(user, folder, sampleStep.isVisible() && sampleCheck.isSelected());
    }

    /** Quitting before finishing leaves nothing behind: nothing is written until Start. */
    @FXML
    private void onQuit() {
        Platform.exit();
    }
}
