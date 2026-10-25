package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.GuestUser;
import com.shelfwise.core.model.LocalUser;
import com.shelfwise.core.model.User;
import com.shelfwise.core.storage.BackupManager;
import com.shelfwise.core.storage.CollectionMerger;
import com.shelfwise.core.storage.CsvExporter;
import com.shelfwise.core.storage.CsvImporter;
import com.shelfwise.core.storage.Exporter;
import com.shelfwise.core.storage.ImportReport;
import com.shelfwise.core.storage.Importer;
import com.shelfwise.core.storage.JsonExporter;
import com.shelfwise.core.storage.JsonImporter;
import com.shelfwise.core.storage.ParsedImport;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The Settings page (E45, E46, E107-E110, E159-E164): one scrolling column
 * of cards - Collection, Data, Book lookup, Appearance, User.
 * <p>
 * Two kinds of setting are saved in two places. The currency belongs to the
 * collection, so it is saved with it in the database ({@link Shell#saveChanges()}).
 * The Google key, the theme and the user belong to this install, so they go
 * to {@code settings.json} ({@link Session#saveSettings()}).
 */
public class SettingsController extends PageController {

    /** D15: the short list shown first; "Others…" opens every ISO currency. */
    private static final String[] COMMON_CURRENCIES = {"INR", "USD", "EUR", "GBP", "JPY"};
    private static final String OTHERS = "Others…";

    @FXML private ComboBox<String> currencyChoice;
    @FXML private Label dataFolderLabel;
    @FXML private PasswordField keyHidden;
    @FXML private TextField keyShown;
    @FXML private ToggleButton showKey;
    @FXML private Button saveKey;
    @FXML private ToggleButton darkToggle;
    @FXML private ToggleButton lightToggle;
    @FXML private VBox userCard;

    /** True while the page sets the currency box itself, so the listener does not act on it. */
    private boolean fillingCurrency;

    @FXML
    private void initialize() {
        ToggleGroup themes = new ToggleGroup();
        darkToggle.setToggleGroup(themes);
        lightToggle.setToggleGroup(themes);

        // E108: the visible and the masked field share one text: binding
        // both ways means typing in either changes the other.
        keyShown.textProperty().bindBidirectional(keyHidden.textProperty());
        keyHidden.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                // E159: Save only does something when the key was edited.
                saveKey.setDisable(!keyEdited());
            }
        });

        currencyChoice.valueProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                if (!fillingCurrency && now != null) {
                    onCurrencyPicked(now);
                }
            }
        });
    }

    /**
     * Every value is read afresh: a restore or "Keep theirs" may have changed
     * the currency, and a key edit dropped on leaving (E159) is replaced by
     * the saved one.
     */
    @Override
    public void shown() {
        dataFolderLabel.setText(session.getDataFolder().toString());
        keyHidden.setText(savedKey());
        fillCurrencies(false);
        syncTheme(shell.isDark());
        buildUserCard();
    }

    /** E159: an unsaved key asks before the page is left. */
    @Override
    public boolean canLeave() {
        if (!keyEdited()) {
            return true;
        }
        ButtonType save = new ButtonType("Save");
        ButtonType discard = new ButtonType("Discard");
        ButtonType keep = new ButtonType("Keep editing", ButtonType.CANCEL.getButtonData());
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Save the Google Books key you typed?", keep, discard, save);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        Optional<ButtonType> answer = alert.showAndWait();
        if (!answer.isPresent() || answer.get() == keep) {
            return false;
        }
        if (answer.get() == save) {
            // A failed save keeps the user here, with the key still typed.
            return saveKey();
        }
        keyHidden.setText(savedKey());
        return true;
    }

    /** E163: the sidebar toggle changed the theme; keep this page's switch in step. */
    @Override
    public void themeChanged(boolean dark) {
        syncTheme(dark);
    }

    // ------------------------------------------------------------------
    // Collection (D15)
    // ------------------------------------------------------------------

    /**
     * Fills the currency box: the short list plus "Others…", or every ISO
     * currency once "Others…" is picked. Each entry reads "INR — Indian
     * Rupee"; the first three letters are the code.
     */
    private void fillCurrencies(boolean all) {
        String current = session.getManager().getCurrencyCode();
        List<String> codes = new ArrayList<>();
        if (all) {
            Set<Currency> every = Currency.getAvailableCurrencies();
            List<Currency> sorted = new ArrayList<>(every);
            Collections.sort(sorted, new Comparator<Currency>() {
                @Override
                public int compare(Currency a, Currency b) {
                    return a.getCurrencyCode().compareTo(b.getCurrencyCode());
                }
            });
            for (int i = 0; i < sorted.size(); i++) {
                codes.add(sorted.get(i).getCurrencyCode());
            }
        } else {
            for (int i = 0; i < COMMON_CURRENCIES.length; i++) {
                codes.add(COMMON_CURRENCIES[i]);
            }
            // A currency outside the short list must still show as chosen.
            if (!codes.contains(current)) {
                codes.add(current);
            }
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            items.add(describe(codes.get(i)));
        }
        if (!all) {
            items.add(OTHERS);
        }
        fillingCurrency = true;
        currencyChoice.getItems().setAll(items);
        currencyChoice.setValue(describe(current));
        fillingCurrency = false;
    }

    private static String describe(String code) {
        return code + " — " + Currency.getInstance(code).getDisplayName(Locale.ENGLISH);
    }

    private void onCurrencyPicked(String entry) {
        if (OTHERS.equals(entry)) {
            // Changing the list from inside its own change listener confuses
            // the ComboBox, so the change waits until this event is over.
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    fillCurrencies(true);
                    currencyChoice.show();
                }
            });
            return;
        }
        String code = entry.substring(0, 3);
        if (!code.equals(session.getManager().getCurrencyCode())) {
            session.getManager().setCurrencyCode(code);
            // The currency is stored in the database with the books (D15).
            shell.saveChanges();
            shell.toast("Currency set to " + code + "; prices are not converted");
        }
    }

    /** Opens the data folder in Finder. Native and can block, so not on the JavaFX thread (§8). */
    @FXML
    private void onOpenFolder() {
        final File folder = session.getDataFolder().toFile();
        Thread opener = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Desktop.getDesktop().open(folder);
                } catch (IOException | UnsupportedOperationException e) {
                    final String reason = e.getMessage();
                    // Only the JavaFX thread may touch the window.
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            shell.toast("Could not open the folder: " + reason);
                        }
                    });
                }
            }
        });
        // A daemon thread never keeps the app from quitting.
        opener.setDaemon(true);
        opener.start();
    }

    // ------------------------------------------------------------------
    // Data: export and import
    // ------------------------------------------------------------------

    @FXML
    private void onExportCsv() {
        chooseAndExport(new CsvExporter(), "CSV");
    }

    @FXML
    private void onExportJson() {
        chooseAndExport(new JsonExporter(), "JSON");
    }

    /**
     * One method serves both formats: it takes the abstract {@link Exporter},
     * and the object passed in decides how the file is written - runtime
     * polymorphism over an abstract class.
     */
    private void chooseAndExport(Exporter exporter, String kind) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + kind);
        chooser.setInitialFileName("shelfwise." + exporter.getFileExtension());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                kind + " files", "*." + exporter.getFileExtension()));
        File file = chooser.showSaveDialog(shell.getWindow());
        if (file == null) {
            return;
        }
        try {
            exporter.export(session.getManager().toCollectionData(), file.toPath());
            shell.toast("Exported to " + file.getName());
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, "Could not export: " + e.getMessage());
        }
    }

    @FXML
    private void onImportCsv() {
        chooseAndImport(new CsvImporter(), "CSV", "csv");
    }

    @FXML
    private void onImportJson() {
        chooseAndImport(new JsonImporter(), "JSON", "json");
    }

    /** The import counterpart of {@link #chooseAndExport}: {@link Importer} is abstract too. */
    private void chooseAndImport(Importer importer, String kind, String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import " + kind);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(kind + " files", "*." + extension));
        File file = chooser.showOpenDialog(shell.getWindow());
        if (file == null) {
            return;
        }
        ParsedImport parsed;
        try {
            parsed = importer.read(file.toPath());
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, "Could not read " + file.getName() + ": " + e.getMessage());
            return;
        }
        String currency = session.getManager().getCurrencyCode();
        if (parsed.currencyDiffersFrom(currency)) {
            ButtonType go = new ButtonType("Import anyway");
            Alert check = new Alert(Alert.AlertType.WARNING, "The file's prices are in " + parsed.getCurrencyCode()
                    + " but the collection uses " + currency + ". Prices are not converted.", ButtonType.CANCEL, go);
            check.setHeaderText(null);
            check.initOwner(shell.getWindow());
            Optional<ButtonType> answer = check.showAndWait();
            if (!answer.isPresent() || answer.get() != go) {
                return;
            }
        }

        ImportReport report;
        try {
            report = importInto(parsed);
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, "Could not import: " + e.getMessage());
            return;
        }
        alert(Alert.AlertType.INFORMATION, reportText(report));
        // E161: Undo stays on offer for the toast's 6 seconds.
        shell.toast(report.getSummary(), "Undo", new Runnable() {
            @Override
            public void run() {
                undoImport();
            }
        });
    }

    /**
     * Merges an import into the collection (merge, never replace: D17) and
     * saves. The database is copied first, so the import can be undone
     * (E161). Shows no dialog, so it can be checked on its own.
     *
     * @return what the merge did
     * @throws IOException if the copy for Undo cannot be made; nothing is merged then
     */
    ImportReport importInto(ParsedImport parsed) throws IOException {
        session.snapshotBeforeImport();
        ImportReport report = new CollectionMerger().merge(parsed, session.getManager());
        shell.saveChanges();
        return report;
    }

    /** E161/E162: back to the database as it was just before the import. */
    void undoImport() {
        try {
            session.undoImport();
        } catch (IOException e) {
            shell.toast("Couldn't undo the import: " + e.getMessage());
            return;
        }
        shell.collectionReplaced();
        shell.toast("Import undone");
    }

    /** The report's summary, then its first 10 problems; a long list would not fit the dialog. */
    private static String reportText(ImportReport report) {
        StringBuilder text = new StringBuilder(report.getSummary()).append('.');
        List<String> problems = report.getProblems();
        for (int i = 0; i < problems.size() && i < 10; i++) {
            text.append("\n• ").append(problems.get(i));
        }
        if (problems.size() > 10) {
            text.append("\n… and ").append(problems.size() - 10).append(" more");
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // Data: backups (E46, E160)
    // ------------------------------------------------------------------

    /**
     * One backup in the Restore list. The list shows {@link #toString()}, so
     * the file and its readable label travel together and the chosen row
     * leads straight to its file.
     */
    private static final class BackupRow {
        private final Path file;
        private final String when;
        private final String label;

        BackupRow(Path file, String when, String label) {
            this.file = file;
            this.when = when;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @FXML
    private void onBackUpNow() {
        try {
            session.backUpNow();
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, "Could not back up: " + e.getMessage());
            return;
        }
        shell.toast("Backed up · the last " + BackupManager.DEFAULT_KEEP + " are kept");
    }

    /**
     * E46/E160: pick a backup and put it in place of the collection. The
     * current database is backed up first, so this can be undone by
     * restoring that backup.
     */
    @FXML
    private void onRestore() {
        List<BackupRow> rows;
        try {
            rows = backupRows();
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, "Could not read the backups: " + e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "There are no backups yet. One is made each time ShelfWise starts.");
            return;
        }
        ListView<BackupRow> list = new ListView<>();
        list.getItems().setAll(rows);
        list.setPrefHeight(Math.min(rows.size(), 5) * 28 + 4);
        list.getSelectionModel().select(0);
        ButtonType restore = new ButtonType("Restore");
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, null, ButtonType.CANCEL, restore);
        dialog.setHeaderText("Choose a backup. The current collection is backed up first, so this can be undone.");
        dialog.getDialogPane().setContent(list);
        dialog.initOwner(shell.getWindow());
        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isPresent() && answer.get() == restore) {
            restore(list.getSelectionModel().getSelectedItem());
        }
    }

    private void restore(BackupRow row) {
        try {
            session.restore(row.file);
        } catch (IOException e) {
            alert(Alert.AlertType.ERROR, "Could not restore: " + e.getMessage());
            return;
        }
        shell.collectionReplaced();
        shell.toast("Restored the backup from " + row.when + " · the previous state was backed up first");
    }

    /** One row per backup, newest first: "24 Oct 2026, 09:12  ·  148 KB". */
    private List<BackupRow> backupRows() throws IOException {
        List<BackupRow> rows = new ArrayList<>();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
        List<Path> files = session.listBackups();
        // listBackups is oldest first; the newest is the likely choice.
        for (int i = files.size() - 1; i >= 0; i--) {
            Path file = files.get(i);
            LocalDateTime taken = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
            String when = format.format(taken);
            rows.add(new BackupRow(file, when, when + "  ·  " + Math.max(1, Files.size(file) / 1024) + " KB"));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Book lookup (E108, E159) and appearance
    // ------------------------------------------------------------------

    @FXML
    private void onShowKey() {
        boolean show = showKey.isSelected();
        keyShown.setVisible(show);
        keyHidden.setVisible(!show);
        showKey.setText(show ? "Hide" : "Show");
    }

    /** The Save button, or Enter in either key field. */
    @FXML
    private void onSaveKey() {
        if (keyEdited()) {
            saveKey();
        }
    }

    /**
     * Writes the typed key to the settings file. The lookup reads it from
     * the settings each time ({@link Session#newLookup()}), so it is used
     * from the next lookup on.
     *
     * @return true if it was saved
     */
    private boolean saveKey() {
        String key = keyHidden.getText().trim();
        String before = session.getSettings().getGoogleApiKey();
        // An empty field means "no key": Google is then asked anonymously (L7).
        session.getSettings().setGoogleApiKey(key.isEmpty() ? null : key);
        try {
            session.saveSettings();
        } catch (IOException e) {
            // Put the old key back, so memory and file still agree.
            session.getSettings().setGoogleApiKey(before);
            shell.toast("The key could not be saved: " + e.getMessage());
            return false;
        }
        keyHidden.setText(key);
        saveKey.setDisable(true);
        shell.toast(key.isEmpty() ? "Google Books key removed" : "Google Books key saved");
        return true;
    }

    /** @return the key as saved, or "" for none */
    private String savedKey() {
        String key = session.getSettings().getGoogleApiKey();
        return key == null ? "" : key;
    }

    /** @return true if the typed key differs from the saved one */
    private boolean keyEdited() {
        return !keyHidden.getText().trim().equals(savedKey());
    }

    @FXML
    private void onDark() {
        pickTheme(true);
    }

    @FXML
    private void onLight() {
        pickTheme(false);
    }

    /** The window switches and remembers the theme, then tells every page, this one included (E163). */
    private void pickTheme(boolean dark) {
        shell.setDark(dark);
    }

    private void syncTheme(boolean dark) {
        darkToggle.setSelected(dark);
        lightToggle.setSelected(!dark);
    }

    // ------------------------------------------------------------------
    // User (E110)
    // ------------------------------------------------------------------

    /**
     * Rebuilds the User card for the kind of user there is now. The card
     * asks the {@link User} what it is through its abstract methods
     * ({@code requiresPassword}, {@code getProfileType}) - runtime
     * polymorphism - and only casts where a {@link LocalUser}-only method
     * is needed.
     */
    private void buildUserCard() {
        userCard.getChildren().clear();
        Label heading = new Label("USER");
        heading.getStyleClass().add("section-label");
        userCard.getChildren().add(heading);
        User user = session.getSettings().getUser();
        // A Guest's name is usually just "Guest", so it is only shown for a Local user.
        Label summary = new Label(user.requiresPassword()
                ? "Local user  ·  " + user.getDisplayName() + "  ·  password asked on every launch"
                : "Guest  ·  no password");
        userCard.getChildren().add(summary);

        if (!user.requiresPassword()) {
            userCard.getChildren().add(row(button("Set a password", "mdi2a-account-lock-outline", new Runnable() {
                @Override public void run() { showSetPasswordForm(); }
            })));
            return;
        }
        final TextField name = new TextField(user.getDisplayName());
        name.setPrefWidth(240);
        Button rename = button("Rename", "mdi2p-pencil-outline", new Runnable() {
            @Override public void run() { rename(name.getText()); }
        });
        HBox nameRow = new HBox(10, settingName("Name"), name, rename);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        userCard.getChildren().add(nameRow);
        userCard.getChildren().add(row(
                button("Change password", "mdi2l-lock-outline", new Runnable() {
                    @Override public void run() { showChangePasswordForm(); }
                }),
                button("Remove password", "mdi2a-account-outline", new Runnable() {
                    @Override public void run() { showRemovePasswordForm(); }
                })));
    }

    /**
     * Writes the user to the settings file after a change, then rebuilds
     * the card and confirms.
     *
     * @param done the toast for a successful save
     */
    private void saveUser(String done) {
        buildUserCard();
        try {
            session.saveSettings();
        } catch (IOException e) {
            shell.toast("Changed for now, but could not be saved: " + e.getMessage());
            return;
        }
        shell.toast(done);
    }

    private void rename(String newName) {
        try {
            session.getSettings().getUser().setDisplayName(newName);
        } catch (IllegalArgumentException e) {
            shell.toast(e.getMessage());
            return;
        }
        saveUser("Name changed");
    }

    /** Guest to Local: a name and a password typed twice. */
    private void showSetPasswordForm() {
        final TextField name = new TextField(session.getSettings().getUser().getDisplayName());
        final PasswordField first = new PasswordField();
        final PasswordField again = new PasswordField();
        final Label error = errorLabel();
        showForm(new HBox[] {formRow("Name", name), formRow("Password", first), formRow("Repeat it", again)},
                error, "Set password", new Runnable() {
                    @Override
                    public void run() {
                        String problem = setPassword(name.getText(), first.getText(), again.getText());
                        if (problem != null) {
                            error.setText(problem);
                        }
                    }
                });
    }

    /**
     * Turns the Guest into a {@link LocalUser}. Package-private so the
     * check can be run without the form.
     *
     * @return null when done, or what is wrong
     */
    String setPassword(String name, String password, String repeated) {
        if (!password.equals(repeated)) {
            return "The two passwords are different.";
        }
        LocalUser local;
        try {
            local = new LocalUser(name, password);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        session.getSettings().setUser(local);
        saveUser("Password set: it will be asked on every launch");
        return null;
    }

    private void showChangePasswordForm() {
        final PasswordField current = new PasswordField();
        final PasswordField first = new PasswordField();
        final PasswordField again = new PasswordField();
        final Label error = errorLabel();
        showForm(new HBox[] {formRow("Current password", current), formRow("New password", first),
                formRow("Repeat it", again)}, error, "Change password", new Runnable() {
                    @Override
                    public void run() {
                        String problem = changePassword(current.getText(), first.getText(), again.getText());
                        if (problem != null) {
                            error.setText(problem);
                        }
                    }
                });
    }

    /** @return null when done, or what is wrong */
    String changePassword(String current, String password, String repeated) {
        if (!password.equals(repeated)) {
            return "The two new passwords are different.";
        }
        try {
            ((LocalUser) session.getSettings().getUser()).changePassword(current, password);
        } catch (IllegalArgumentException e) {
            // Wrong current password, or a blank new one.
            return e.getMessage();
        }
        saveUser("Password changed");
        return null;
    }

    private void showRemovePasswordForm() {
        final PasswordField current = new PasswordField();
        final Label error = errorLabel();
        showForm(new HBox[] {formRow("Current password", current)}, error, "Remove password", new Runnable() {
            @Override
            public void run() {
                String problem = removePassword(current.getText());
                if (problem != null) {
                    error.setText(problem);
                }
            }
        });
    }

    /**
     * Local to Guest, after the current password; the name is kept.
     *
     * @return null when done, or what is wrong
     */
    String removePassword(String current) {
        User user = session.getSettings().getUser();
        if (!user.authenticate(current)) {
            return "Current password is incorrect.";
        }
        session.getSettings().setUser(new GuestUser(user.getDisplayName()));
        saveUser("Password removed: ShelfWise opens without asking");
        return null;
    }

    /** Replaces the card's buttons with a small form: rows, an error line, Cancel and the action. */
    private void showForm(HBox[] rows, Label error, String action, final Runnable onAction) {
        buildUserCard();
        // Drop the action buttons the card was built with; keep the heading and summary.
        userCard.getChildren().remove(2, userCard.getChildren().size());
        for (int i = 0; i < rows.length; i++) {
            userCard.getChildren().add(rows[i]);
        }
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("plain-button");
        cancel.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                buildUserCard();
            }
        });
        Button go = new Button(action);
        go.getStyleClass().add("accent-button");
        go.setDefaultButton(true);
        go.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                onAction.run();
            }
        });
        userCard.getChildren().addAll(error, row(cancel, go));
        rows[0].getChildren().get(1).requestFocus();
    }

    private HBox formRow(String label, TextField field) {
        field.setPrefWidth(240);
        HBox row = new HBox(10, settingName(label), field);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label settingName(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("setting-name");
        return label;
    }

    private static Label errorLabel() {
        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        return error;
    }

    private static HBox row(Button... buttons) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(buttons);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    private static Button button(String text, String icon, final Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("plain-button");
        button.setGraphic(new FontIcon(icon));
        button.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
        return button;
    }

    private void alert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        alert.showAndWait();
    }
}
