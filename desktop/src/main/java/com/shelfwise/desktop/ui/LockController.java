package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.User;
import javafx.application.Platform;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.util.Duration;

/**
 * The lock screen a Local user sees on every launch
 * (E44, E105, E106). It only asks the {@link User} whether the password is
 * right; how the check works (salted SHA-256) stays inside the model.
 */
public class LockController {

    /** Told when the right password is entered. */
    public interface Unlocked {
        void unlocked();
    }

    @FXML private Label welcome;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Label forgotNote;

    private Unlocked unlocked;
    private User user;

    public void open(User user, Unlocked unlocked) {
        this.user = user;
        this.unlocked = unlocked;
        welcome.setText("Welcome back, " + user.getDisplayName());
        passwordField.clear();
        errorLabel.setText("");
        forgotNote.setVisible(false);
        forgotNote.setManaged(false);
        passwordField.requestFocus();
    }

    /** E105: a wrong password shakes the field and clears it; there is no limit on tries. */
    @FXML
    private void onUnlock() {
        if (user.authenticate(passwordField.getText())) {
            unlocked.unlocked();
            return;
        }
        errorLabel.setText("Wrong password");
        passwordField.clear();
        passwordField.requestFocus();
        shake();
    }

    /**
     * Slides the field 6 px right and back, four times. A TranslateTransition
     * animates on the JavaFX thread by itself, so nothing here blocks.
     */
    private void shake() {
        TranslateTransition shake = new TranslateTransition(Duration.millis(50), passwordField);
        shake.setFromX(0);
        shake.setByX(6);
        shake.setCycleCount(4);
        // Each odd cycle runs backwards, so the field ends where it started.
        shake.setAutoReverse(true);
        shake.play();
    }

    /** E106: says plainly what the password is and how to reset it. */
    @FXML
    private void onForgot() {
        boolean show = !forgotNote.isVisible();
        forgotNote.setVisible(show);
        forgotNote.setManaged(show);
    }

    @FXML
    private void onQuit() {
        Platform.exit();
    }
}
