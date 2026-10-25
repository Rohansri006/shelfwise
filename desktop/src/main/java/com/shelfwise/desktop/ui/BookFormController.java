package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.metadata.LookupResult;
import com.shelfwise.core.metadata.MetadataLookup;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.service.CollectionManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.awt.Toolkit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Single add/edit form, in three modes.
 * <ul>
 *   <li><b>NEW</b> - adding a book: Save and Save &amp; add another (E64);</li>
 *   <li><b>EDIT</b> - editing one from Browse: Save;</li>
 *   <li><b>QUEUE</b> - editing a Bulk scan row (E37): Done writes back to
 *       the row, not the collection.</li>
 * </ul>
 * <p>
 * <strong>How Save validates (E63).</strong> Every field is first applied
 * to a throwaway "scratch" {@link Book}. The model's own setters do the
 * checking - each throws {@code IllegalArgumentException} with a message
 * naming the bad value - and every message is collected rather than
 * stopping at the first. Only if the scratch book took every value is the
 * real book touched, so a half-applied save is impossible. The rules
 * therefore live once, in the model, not again in the screen.
 * <p>
 * <strong>Saving (ADR 0001).</strong> Every change to the collection made
 * here - Save, Add as copy, Restore, a new series - is followed at once by
 * {@link Shell#saveChanges()}. A cover is written to {@code covers/} as a
 * real file (E153).
 * <p>
 * It is not a page of its own: the Add page ({@link AddController}) holds
 * it, and is told through {@link Host} when the user is done.
 */
public class BookFormController {

    /** What the form tells the page around it. */
    interface Host {

        /** A book was saved; addAnother means a blank form should follow. */
        void saved(Book book, boolean addAnother);

        /** The user left without saving (after confirming, if needed). */
        void cancelled();

        /** "Open", "Add as copy" or "Restore" on a duplicate banner: show this book in Browse. */
        void openExisting(Book book);

        /** QUEUE mode: Done pressed with a valid draft. */
        void queueDone(Book draft, Image cover);
    }

    enum Mode { NEW, EDIT, QUEUE }

    private static final String NO_SERIES = "None";
    private static final String NEW_SERIES = "New series…";
    private static final String[] RATINGS = {"No rating", "★", "★★", "★★★",
        "★★★★", "★★★★★"};

    @FXML private HBox banner;
    @FXML private Label bannerText;
    @FXML private HBox bannerActions;
    @FXML private StackPane coverSlot;
    @FXML private Button removeCover;
    @FXML private TextField isbnField;
    @FXML private Button lookUpButton;
    @FXML private Button scanButton;
    @FXML private ToggleButton mirrorToggle;
    @FXML private Label lookupStatus;
    @FXML private ScrollPane formScroll;
    @FXML private TextField titleField;
    @FXML private HBox titleHint;
    @FXML private Label titleHintText;
    @FXML private TextField subtitleField;
    @FXML private TextField publisherField;
    @FXML private TextField yearField;
    @FXML private TextField pagesField;
    @FXML private TextField languageField;
    @FXML private ComboBox<String> seriesChoice;
    @FXML private Label positionLabel;
    @FXML private TextField positionField;
    @FXML private VBox contributorRows;
    @FXML private VBox copyRows;
    @FXML private FlowPane categoryChips;
    @FXML private ComboBox<ReadingStatus> statusChoice;
    @FXML private ComboBox<String> ratingChoice;
    @FXML private VBox sessionRows;
    @FXML private TextArea notesArea;
    @FXML private VBox fieldRows;
    @FXML private Button cancelButton;
    @FXML private Button saveAnotherButton;
    @FXML private Button saveButton;

    private Shell shell;
    private Session session;
    private Host host;

    private Mode mode = Mode.NEW;

    /** EDIT: the book being edited. QUEUE: the row's draft. NEW: null. */
    private Book editing;

    /** A cover picked or downloaded, written to {@code covers/} on Save. */
    private Image pendingCover;
    private boolean coverRemoved;

    /** The categories currently shown as chips. */
    private final List<Category> chosenCategories = new ArrayList<>();

    /** One entry per row on screen, in screen order. */
    private final List<ContributorRow> contributors = new ArrayList<>();
    private final List<CopyRow> copies = new ArrayList<>();
    private final List<SessionRow> sessions = new ArrayList<>();
    private final List<FieldRow> fields = new ArrayList<>();

    /** Controls currently outlined in red (E63). */
    private final List<Node> badFields = new ArrayList<>();

    /** The form's contents when opened; a difference on leaving means unsaved changes (E40). */
    private String cleanSignature = "";

    /**
     * Bumped every time the form is opened. A lookup started on an earlier
     * opening compares its number with this when it finishes and, if they
     * differ, throws its answer away rather than filling a different book.
     */
    private int opening = 0;

    /**
     * E72: restarted on every change to the ISBN field; when it runs out
     * (typing has paused for 0.4 s) the form looks the ISBN up by itself. A
     * USB scanner "types" a whole ISBN in a few milliseconds, so it also
     * fires once, after the last digit.
     */
    private final PauseTransition autoLookup = new PauseTransition(Duration.millis(400));

    /** E72: the ISBN-13 last looked up, so one ISBN is never looked up twice in a row. */
    private String lastLookedUp;

    /**
     * True while the form fills its own fields on opening. Text set by the
     * program is not the user typing, so it must not start an auto-lookup
     * (E72: never just because the form was opened).
     */
    private boolean filling = false;

    /** E71: the camera while Scan is on, else null. */
    private CameraFeed scanCamera;

    /** The live picture while Scan is on, so Mirror can flip it; else null. */
    private ImageView scanView;

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    @FXML
    private void initialize() {
        statusChoice.getItems().addAll(ReadingStatus.values());
        ratingChoice.getItems().addAll(RATINGS);
        seriesChoice.valueProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                if (NEW_SERIES.equals(now)) {
                    createSeries(old);
                }
                boolean inSeries = now != null && !NO_SERIES.equals(now) && !NEW_SERIES.equals(now);
                positionLabel.setVisible(inSeries);
                positionField.setVisible(inSeries);
            }
        });
        // D9: a soft, non-blocking hint when another book has the same title.
        titleField.focusedProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> obs, Boolean was, Boolean now) {
                if (!now) {
                    checkTitle();
                }
            }
        });
        // E72: every change restarts the wait; only a pause in typing gets through.
        isbnField.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                if (!filling) {
                    autoLookup.playFromStart();
                }
            }
        });
        autoLookup.setOnFinished(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                autoLookUpIfComplete();
            }
        });
    }

    /**
     * Called once by the Add page after loading.
     *
     * @param shell   the window: autosave, toasts, dialog owner
     * @param session the settings and the open collection
     * @param host    the Add page
     */
    void setUp(Shell shell, Session session, Host host) {
        this.shell = shell;
        this.session = session;
        this.host = host;
    }

    /**
     * Asked for on every use, never kept: "Keep theirs" (E47) replaces the
     * manager with a freshly loaded one (see {@link Session}).
     */
    private CollectionManager manager() {
        return session.getManager();
    }

    private Path dataFolder() {
        return session.getDataFolder();
    }

    /** A blank form for a new book. */
    void openNew() {
        open(Mode.NEW, null);
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                isbnField.requestFocus();
            }
        });
    }

    /** The form filled from a book in the collection. */
    void openEdit(Book book) {
        open(Mode.EDIT, book);
    }

    /**
     * E114: a book just bought from the wishlist. It holds only the entry's
     * title, ISBN and author, so the lookup runs at once to fill the rest;
     * in Edit mode it fills only empty fields (E35).
     */
    void openEditAndLookUp(Book book) {
        open(Mode.EDIT, book);
        if (book.getIsbn() != null) {
            onLookUp();
        }
    }

    /**
     * E37: the form filled from a Bulk scan row's draft.
     *
     * @param draft the row's book, not in the collection
     * @param cover the row's downloaded cover, or null
     */
    void openQueueRow(Book draft, Image cover) {
        open(Mode.QUEUE, draft);
        // A book no catalogue knew carries a placeholder title (Book needs
        // one); clear it so the user types the real one.
        if (BulkScanController.UNTITLED.equals(draft.getTitle())) {
            titleField.clear();
            // The row's lookup already failed for this ISBN; do not repeat it
            // unless the user changes the ISBN.
            lastLookedUp = draft.getIsbn();
            showBanner("warn", "No catalogue knew this ISBN. Fill the details in by hand.");
        }
        pendingCover = cover;
        showCover();
        cleanSignature = signature();
    }

    private void open(Mode newMode, Book book) {
        opening++;
        stopScan();
        autoLookup.stop();
        lastLookedUp = null;
        // Everything set from here to the end is the program, not the user.
        filling = true;
        mode = newMode;
        editing = book;
        pendingCover = null;
        coverRemoved = false;
        hideBanner();
        clearErrors();
        titleHint.setVisible(false);
        titleHint.setManaged(false);
        lookupStatus.setText("");
        lookUpButton.setDisable(false);
        // E71/E72: scanning and auto-lookup are for new books only.
        scanButton.setVisible(mode != Mode.EDIT);
        scanButton.setManaged(mode != Mode.EDIT);
        fillSeriesChoices();

        clearRows();
        if (book == null) {
            isbnField.clear();
            titleField.clear();
            subtitleField.clear();
            publisherField.clear();
            yearField.clear();
            pagesField.clear();
            languageField.clear();
            seriesChoice.setValue(NO_SERIES);
            positionField.clear();
            statusChoice.setValue(ReadingStatus.UNREAD);
            ratingChoice.setValue(RATINGS[0]);
            notesArea.clear();
            addCopyRow(null);
        } else {
            fillFrom(book);
        }
        showCover();

        saveAnotherButton.setVisible(mode == Mode.NEW);
        saveAnotherButton.setManaged(mode == Mode.NEW);
        saveButton.setText(mode == Mode.QUEUE ? "Done" : "Save");
        cancelButton.setText(mode == Mode.QUEUE ? "Back to queue" : "Cancel");
        formScroll.setVvalue(0);
        cleanSignature = signature();
        filling = false;
    }

    /** Fills every control from a book (EDIT and QUEUE). */
    private void fillFrom(Book book) {
        isbnField.setText(book.getIsbn() == null ? "" : book.getIsbn());
        titleField.setText(book.getTitle());
        subtitleField.setText(orEmpty(book.getSubtitle()));
        publisherField.setText(orEmpty(book.getPublisher()));
        yearField.setText(book.getPublicationYear() == 0 ? "" : String.valueOf(book.getPublicationYear()));
        pagesField.setText(book.getPages() == 0 ? "" : String.valueOf(book.getPages()));
        languageField.setText(orEmpty(book.getLanguage()));
        Series series = book.getSeriesId() == null ? null : manager().findSeriesById(book.getSeriesId());
        seriesChoice.setValue(series == null ? NO_SERIES : series.getName());
        positionField.setText(book.getSeriesPosition() == 0 ? "" : String.valueOf(book.getSeriesPosition()));
        for (int i = 0; i < book.getContributors().size(); i++) {
            addContributorRow(book.getContributors().get(i));
        }
        for (int i = 0; i < book.getCopies().size(); i++) {
            addCopyRow(book.getCopies().get(i));
        }
        for (int i = 0; i < book.getCategoryIds().size(); i++) {
            Category c = manager().findCategoryById(book.getCategoryIds().get(i));
            if (c != null) {
                chosenCategories.add(c);
            }
        }
        rebuildChips();
        statusChoice.setValue(book.getReadingStatus());
        ratingChoice.setValue(RATINGS[book.getRating()]);
        for (int i = 0; i < book.getReadingSessions().size(); i++) {
            addSessionRow(book.getReadingSessions().get(i));
        }
        notesArea.setText(orEmpty(book.getNotes()));
        List<String> keys = new ArrayList<>(book.getCustomFields().keySet());
        for (int i = 0; i < keys.size(); i++) {
            addFieldRow(keys.get(i), book.getCustomFields().get(keys.get(i)));
        }
    }

    private void clearRows() {
        contributors.clear();
        contributorRows.getChildren().clear();
        copies.clear();
        copyRows.getChildren().clear();
        sessions.clear();
        sessionRows.getChildren().clear();
        fields.clear();
        fieldRows.getChildren().clear();
        chosenCategories.clear();
        rebuildChips();
    }

    // ------------------------------------------------------------------
    // Leaving (E40)
    // ------------------------------------------------------------------

    /**
     * Asks before throwing away changes. An unchanged form leaves silently.
     *
     * @return true if it is fine to leave
     */
    boolean confirmLeave() {
        if (signature().equals(cleanSignature)) {
            return true;
        }
        ButtonType discard = new ButtonType("Discard");
        ButtonType keep = new ButtonType("Keep editing", ButtonType.CANCEL.getButtonData());
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Discard your changes to this book?", keep, discard);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isPresent() && answer.get() == discard) {
            // Discarded: the form now counts as clean, so the window's own
            // "may I leave?" check that follows does not ask a second time.
            markClean();
            return true;
        }
        return false;
    }

    /** Takes the form as it is now as unchanged (after Save, Discard, or leaving for an existing book). */
    private void markClean() {
        cleanSignature = signature();
    }

    @FXML
    private void onCancel() {
        if (confirmLeave()) {
            stopScan();
            host.cancelled();
        }
    }

    /**
     * Everything the user can change, as one string. Comparing two of these
     * is a cheap, complete "has anything changed?" test: no flag has to be
     * set by every one of dozens of controls.
     */
    private String signature() {
        StringBuilder s = new StringBuilder();
        TextField[] texts = {isbnField, titleField, subtitleField, publisherField, yearField, pagesField,
            languageField, positionField};
        for (int i = 0; i < texts.length; i++) {
            s.append(texts[i].getText()).append('|');
        }
        s.append(seriesChoice.getValue()).append('|').append(statusChoice.getValue()).append('|')
                .append(ratingChoice.getValue()).append('|').append(notesArea.getText()).append('|')
                .append(pendingCover != null).append(coverRemoved).append('|');
        for (int i = 0; i < contributors.size(); i++) {
            s.append(contributors.get(i).name.getText()).append(contributors.get(i).role.getValue()).append(';');
        }
        for (int i = 0; i < copies.size(); i++) {
            CopyRow r = copies.get(i);
            s.append(r.format.getValue()).append(r.condition.getValue()).append(r.location)
                    .append(r.date.getValue()).append(r.price.getText()).append(';');
        }
        for (int i = 0; i < chosenCategories.size(); i++) {
            s.append(chosenCategories.get(i).getId()).append(';');
        }
        for (int i = 0; i < sessions.size(); i++) {
            s.append(sessions.get(i).start.getValue()).append(sessions.get(i).end.getValue()).append(';');
        }
        for (int i = 0; i < fields.size(); i++) {
            s.append(fields.get(i).key.getText()).append('=').append(fields.get(i).value.getText()).append(';');
        }
        return s.toString();
    }

    // ------------------------------------------------------------------
    // Look up (E35, E55)
    // ------------------------------------------------------------------

    @FXML
    private void onLookUp() {
        hideBanner();
        clearErrors();
        final String isbn13;
        try {
            isbn13 = IsbnValidator.toIsbn13(isbnField.getText());
        } catch (InvalidISBNException e) {
            markBad(isbnField);
            showBanner("error", e.getMessage());
            return;
        }
        // Counted as looked up even if it turns out to be a duplicate, so the
        // auto-lookup does not show the same banner again.
        lastLookedUp = isbn13;
        Book existing = manager().findBookByIsbn(isbn13);
        if (existing != null && existing != editing) {
            showDuplicate(existing);
            return;
        }
        lookUpButton.setDisable(true);
        lookupStatus.setText("Looking up…");
        final int asked = opening;
        final MetadataLookup lookup = session.newLookup();
        // Network work off the JavaFX thread; only runLater touches the screen.
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LookupResult result = lookup.lookUp(isbn13);
                    final Book found = result.toBook(null);
                    final Image cover = CoverArt.download(result.getMetadata().getCoverUrl());
                    final String from = result.describe();
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            if (asked == opening) {
                                applyLookup(found, cover, from);
                            }
                        }
                    });
                } catch (final Exception e) {
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            if (asked == opening) {
                                lookUpButton.setDisable(false);
                                lookupStatus.setText("");
                                showBanner("warn", "Not found online (" + e.getMessage()
                                        + "). Fill the details in by hand.");
                            }
                        }
                    });
                }
            }
        }, "isbn-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * E72: runs when typing in the ISBN field pauses. Looks up only a new
     * book's complete, valid ISBN that was not just looked up, and never
     * while a lookup is already running (the button is disabled then).
     */
    private void autoLookUpIfComplete() {
        if (mode == Mode.EDIT || lookUpButton.isDisabled() || !IsbnValidator.isValid(isbnField.getText())) {
            return;
        }
        try {
            if (IsbnValidator.toIsbn13(isbnField.getText()).equals(lastLookedUp)) {
                return;
            }
        } catch (InvalidISBNException e) {
            return; // cannot happen after isValid; the compiler still needs the catch
        }
        onLookUp();
    }

    // ------------------------------------------------------------------
    // Scan (E71)
    // ------------------------------------------------------------------

    /** The Scan button: turns the camera on, or off again ("Cancel scan"). */
    @FXML
    private void onScan() {
        if (scanCamera != null) {
            stopScan();
            return;
        }
        ImageView view = new ImageView();
        view.setFitWidth(180);
        view.setFitHeight(255);
        view.setPreserveRatio(true);
        scanView = view;
        applyMirror();
        showMirrorToggle(true);
        Label hint = new Label("Opening the camera…");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);
        StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
        coverSlot.getChildren().setAll(view, hint);
        scanButton.setText("Cancel scan");
        final Label status = hint;
        scanCamera = new CameraFeed(view);
        scanCamera.start(0, new CameraFeed.Listener() {
            @Override
            public void devices(List<String> names) {
                // One camera is enough here; Bulk scan has the chooser.
            }

            @Override
            public void live(String name) {
                status.setText("Hold the barcode up to the camera.");
            }

            @Override
            public void code(String code) {
                scannedIsbn(code);
            }

            @Override
            public void failed(String message) {
                status.setText(message + "\nType the ISBN instead.");
            }
        });
    }

    /**
     * The first valid ISBN the camera reads: stop, fill the field, beep and
     * look it up at once. Codes that are not ISBNs are ignored, and so is
     * anything still arriving from the camera after it was stopped.
     */
    private void scannedIsbn(String code) {
        if (scanCamera == null || !IsbnValidator.isValid(code)) {
            return;
        }
        stopScan();
        // filling: the lookup is started directly below, not by the typing pause.
        filling = true;
        isbnField.setText(code);
        filling = false;
        Toolkit.getDefaultToolkit().beep();
        onLookUp();
    }

    /**
     * Turns the camera off and puts the cover back. Safe to call when it is
     * already off. Also called by the Add page when the user leaves, so the
     * camera is never left running behind another screen.
     */
    void stopScan() {
        if (scanCamera == null) {
            return;
        }
        scanCamera.stop();
        scanCamera = null;
        scanView = null;
        showMirrorToggle(false);
        scanButton.setText("Scan");
        showCover();
    }

    @FXML
    private void onMirror() {
        applyMirror();
    }

    /**
     * Display only, as in Bulk scan: a negative horizontal scale flips the
     * picture so moving a book right moves it right on screen. The frames
     * given to the barcode reader are never flipped, so reading is
     * unaffected. The toggle keeps its setting between scans.
     */
    private void applyMirror() {
        if (scanView != null) {
            scanView.setScaleX(mirrorToggle.isSelected() ? -1 : 1);
        }
    }

    /** Mirror is shown only while the camera is on: there is nothing to flip otherwise. */
    private void showMirrorToggle(boolean shown) {
        mirrorToggle.setVisible(shown);
        mirrorToggle.setManaged(shown);
    }

    /**
     * Puts a lookup's answer into the form. A new book takes every field;
     * an edited one only fills its empty fields (E35), so nothing the user
     * typed is overwritten unasked.
     */
    private void applyLookup(Book found, Image cover, String from) {
        lookUpButton.setDisable(false);
        lookupStatus.setText(from);
        boolean all = mode != Mode.EDIT;
        fillText(titleField, found.getTitle(), all);
        fillText(subtitleField, found.getSubtitle(), all);
        fillText(publisherField, found.getPublisher(), all);
        fillText(yearField, found.getPublicationYear() == 0 ? null : String.valueOf(found.getPublicationYear()), all);
        fillText(pagesField, found.getPages() == 0 ? null : String.valueOf(found.getPages()), all);
        fillText(languageField, found.getLanguage(), all);
        if ((all || contributors.isEmpty()) && !found.getContributors().isEmpty()) {
            contributors.clear();
            contributorRows.getChildren().clear();
            for (int i = 0; i < found.getContributors().size(); i++) {
                addContributorRow(found.getContributors().get(i));
            }
        }
        // An edited book keeps a cover it already has.
        boolean hasCover = pendingCover != null
                || (mode == Mode.EDIT && !coverRemoved && CoverArt.imageFor(editing, dataFolder()) != null);
        if (cover != null && (all || !hasCover)) {
            pendingCover = cover;
            coverRemoved = false;
            showCover();
        }
        checkTitle();
        showBanner("info", "Filled from the lookup. Check the details, then save.");
    }

    private static void fillText(TextField field, String value, boolean overwrite) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (overwrite || field.getText().trim().isEmpty()) {
            field.setText(value);
        }
    }

    /**
     * E35 / D8: the ISBN is already in the collection - live, or in the Trash.
     */
    private void showDuplicate(final Book existing) {
        markBad(isbnField);
        if (existing.isDeleted()) {
            Button restore = new Button("Restore");
            restore.getStyleClass().add("accent-button");
            restore.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    manager().restoreFromTrash(existing.getId());
                    shell.saveChanges();
                    shell.toast("“" + existing.getTitle() + "” restored");
                    leaveFor(existing);
                }
            });
            showBanner("warn", "“" + existing.getTitle() + "” is in the Trash.", restore);
            return;
        }
        Button asCopy = new Button("Add as copy");
        asCopy.getStyleClass().add("accent-button");
        asCopy.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                existing.addCopy(new Copy());
                shell.saveChanges();
                shell.toast("Copy added to “" + existing.getTitle() + "”");
                leaveFor(existing);
            }
        });
        Button openIt = new Button("Open");
        openIt.getStyleClass().add("plain-button");
        openIt.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                leaveFor(existing);
            }
        });
        showBanner("warn", "“" + existing.getTitle() + "” is already in your collection.", asCopy, openIt);
    }

    /**
     * Leaves the form to show a book already in the collection. What was
     * typed here is dropped on purpose - the user chose the existing book -
     * so it is not asked about.
     */
    private void leaveFor(Book existing) {
        stopScan();
        markClean();
        host.openExisting(existing);
    }

    /** D9: same title, different (or no) ISBN - a hint, never a block. */
    private void checkTitle() {
        String title = titleField.getText().trim();
        Book same = null;
        List<CollectionItem> items = manager().getItems();
        for (int i = 0; i < items.size() && !title.isEmpty(); i++) {
            if (items.get(i) != editing && items.get(i).getTitle().equalsIgnoreCase(title)) {
                same = (Book) items.get(i);
            }
        }
        titleHint.setVisible(same != null);
        titleHint.setManaged(same != null);
        if (same != null) {
            titleHintText.setText("You already have a book called “" + same.getTitle()
                    + "”. Different edition? Saving adds a separate book.");
        }
    }

    // ------------------------------------------------------------------
    // Cover
    // ------------------------------------------------------------------

    @FXML
    private void onChangeCover() {
        stopScan();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a cover");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
        File file = chooser.showOpenDialog(shell.getWindow());
        if (file == null) {
            return;
        }
        Image image = new Image(file.toURI().toString());
        if (image.isError()) {
            showBanner("error", "That file is not an image ShelfWise can read.");
            return;
        }
        pendingCover = image;
        coverRemoved = false;
        showCover();
    }

    @FXML
    private void onRemoveCover() {
        stopScan();
        pendingCover = null;
        coverRemoved = true;
        showCover();
    }

    private void showCover() {
        double w = 180;
        double h = 255;
        Node art;
        if (pendingCover != null) {
            art = CoverArt.imageView(pendingCover, w, h);
        } else if (editing != null && !coverRemoved && mode == Mode.EDIT) {
            art = CoverArt.coverFor(editing, dataFolder(), w, h, true);
        } else {
            // A generated tile from whatever title is typed so far.
            String title = titleField.getText().trim();
            art = CoverArt.generated(new Book(title.isEmpty() ? "New book" : title), w, h);
        }
        // While scanning the slot shows the camera; the cover returns on stopScan().
        if (scanCamera == null) {
            coverSlot.getChildren().setAll(art);
        }
        removeCover.setDisable(pendingCover == null && (editing == null || mode != Mode.EDIT || coverRemoved));
    }

    // ------------------------------------------------------------------
    // Sub-lists (E61)
    // ------------------------------------------------------------------

    @FXML
    private void onAddContributor() {
        addContributorRow(null);
    }

    @FXML
    private void onAddCopy() {
        addCopyRow(null);
    }

    @FXML
    private void onAddField() {
        addFieldRow("", "");
    }

    /** Opens a new session today and marks the book Reading. */
    @FXML
    private void onStartReading() {
        SessionRow row = addSessionRow(null);
        row.start.setValue(LocalDate.now());
        statusChoice.setValue(ReadingStatus.READING);
    }

    @FXML
    private void onAddSession() {
        addSessionRow(null);
    }

    /** One contributor: name, role, remove. */
    private static class ContributorRow {
        final TextField name = new TextField();
        final ComboBox<ContributorRole> role = new ComboBox<>();
        HBox node;
    }

    private void addContributorRow(Contributor contributor) {
        final ContributorRow row = new ContributorRow();
        row.name.setPromptText("Name");
        HBox.setHgrow(row.name, Priority.ALWAYS);
        row.role.getItems().addAll(ContributorRole.values());
        row.role.getStyleClass().add("combo");
        row.role.setValue(contributor == null ? ContributorRole.AUTHOR : contributor.getRole());
        if (contributor != null) {
            row.name.setText(contributor.getName());
        }
        row.node = new HBox(8, row.name, row.role, removeButton(new Runnable() {
            @Override
            public void run() {
                contributors.remove(row);
                contributorRows.getChildren().remove(row.node);
            }
        }));
        row.node.setAlignment(Pos.CENTER_LEFT);
        contributors.add(row);
        contributorRows.getChildren().add(row.node);
    }

    /** One copy: format, condition, location (tree popup, E65), purchase date, price. */
    private static class CopyRow {
        Copy original;
        final ComboBox<Format> format = new ComboBox<>();
        final ComboBox<Condition> condition = new ComboBox<>();
        final Button locationButton = new Button();
        Location location;
        final DatePicker date = new DatePicker();
        final TextField price = new TextField();
        HBox node;
    }

    private void addCopyRow(Copy copy) {
        final CopyRow row = new CopyRow();
        row.original = copy;
        row.format.getItems().addAll(Format.values());
        row.format.getStyleClass().add("combo");
        row.condition.getItems().addAll(Condition.values());
        row.condition.getStyleClass().add("combo");
        row.format.setValue(copy == null ? Format.PAPERBACK : copy.getFormat());
        row.condition.setValue(copy == null ? Condition.GOOD : copy.getCondition());
        row.location = copy == null ? null : manager().findLocationById(copy.getLocationId());
        row.locationButton.getStyleClass().add("plain-button");
        row.locationButton.setGraphic(new FontIcon("mdi2m-map-marker-outline"));
        row.locationButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(row.locationButton, Priority.ALWAYS);
        showLocation(row);
        row.locationButton.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                new TreePicker<Location>(new LocationTree(session, shell), "location", "Unshelved",
                        new TreePicker.Chosen<Location>() {
                            @Override
                            public void chosen(Location node) {
                                row.location = node;
                                showLocation(row);
                            }
                        }).showBelow(row.locationButton);
            }
        });
        row.date.setPromptText("Bought on");
        row.date.setPrefWidth(130);
        row.date.setValue(copy == null ? null : copy.getPurchaseDate());
        row.price.setPromptText("Price (" + manager().getCurrencyCode() + ")");
        row.price.setPrefColumnCount(6);
        if (copy != null && copy.hasRecordedPrice()) {
            double paid = copy.getPricePaid();
            // 599.0 shows as "599"; 599.5 stays "599.5".
            row.price.setText(paid == Math.floor(paid) ? String.valueOf((long) paid) : String.valueOf(paid));
        }
        row.node = new HBox(8, row.format, row.condition, row.locationButton, row.date, row.price,
                removeButton(new Runnable() {
                    @Override
                    public void run() {
                        copies.remove(row);
                        copyRows.getChildren().remove(row.node);
                    }
                }));
        row.node.setAlignment(Pos.CENTER_LEFT);
        copies.add(row);
        copyRows.getChildren().add(row.node);
    }

    private static void showLocation(CopyRow row) {
        row.locationButton.setText(row.location == null ? "Unshelved" : row.location.getPath());
    }

    /** One reading session: started, finished (empty = still reading), Finish today. */
    private static class SessionRow {
        ReadingSession original;
        final DatePicker start = new DatePicker();
        final DatePicker end = new DatePicker();
        HBox node;
    }

    private SessionRow addSessionRow(ReadingSession session) {
        final SessionRow row = new SessionRow();
        row.original = session;
        row.start.setPromptText("Started");
        row.end.setPromptText("Finished");
        row.start.setPrefWidth(140);
        row.end.setPrefWidth(140);
        if (session != null) {
            row.start.setValue(session.getStartDate());
            row.end.setValue(session.getEndDate());
        }
        // D13: "Finish today" closes the session and marks the book Finished,
        // as Book.finishReadingSession does when the form is saved.
        Button finish = new Button("Finish today");
        finish.getStyleClass().add("link-button");
        finish.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                row.end.setValue(LocalDate.now());
                statusChoice.setValue(ReadingStatus.FINISHED);
            }
        });
        Label dash = new Label("–");
        row.node = new HBox(8, row.start, dash, row.end, finish, removeButton(new Runnable() {
            @Override
            public void run() {
                sessions.remove(row);
                sessionRows.getChildren().remove(row.node);
            }
        }));
        row.node.setAlignment(Pos.CENTER_LEFT);
        sessions.add(row);
        sessionRows.getChildren().add(row.node);
        return row;
    }

    /** One custom field: name and value. */
    private static class FieldRow {
        final TextField key = new TextField();
        final TextField value = new TextField();
        HBox node;
    }

    private void addFieldRow(String key, String value) {
        final FieldRow row = new FieldRow();
        row.key.setPromptText("Field (e.g. Signed)");
        row.value.setPromptText("Value");
        row.key.setText(key);
        row.value.setText(value);
        HBox.setHgrow(row.value, Priority.ALWAYS);
        row.node = new HBox(8, row.key, row.value, removeButton(new Runnable() {
            @Override
            public void run() {
                fields.remove(row);
                fieldRows.getChildren().remove(row.node);
            }
        }));
        row.node.setAlignment(Pos.CENTER_LEFT);
        fields.add(row);
        fieldRows.getChildren().add(row.node);
    }

    private static Button removeButton(final Runnable onRemove) {
        Button remove = new Button();
        remove.getStyleClass().add("icon-button");
        remove.setGraphic(new FontIcon("mdi2c-close"));
        remove.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                onRemove.run();
            }
        });
        return remove;
    }

    // ------------------------------------------------------------------
    // Categories (E62) and series (E66)
    // ------------------------------------------------------------------

    private void rebuildChips() {
        categoryChips.getChildren().clear();
        for (int i = 0; i < chosenCategories.size(); i++) {
            final Category c = chosenCategories.get(i);
            HBox chip = BrowseController.chip(c.getName(), new Runnable() {
                @Override
                public void run() {
                    chosenCategories.remove(c);
                    rebuildChips();
                }
            });
            categoryChips.getChildren().add(chip);
        }
        final Button add = new Button("Category");
        add.getStyleClass().add("link-button");
        add.setGraphic(new FontIcon("mdi2p-plus"));
        add.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                new TreePicker<Category>(new CategoryTree(session, shell), "category", null,
                        new TreePicker.Chosen<Category>() {
                            @Override
                            public void chosen(Category node) {
                                if (node != null && !chosenCategories.contains(node)) {
                                    chosenCategories.add(node);
                                    rebuildChips();
                                }
                            }
                        }).showBelow(add);
            }
        });
        categoryChips.getChildren().add(add);
    }

    private void fillSeriesChoices() {
        seriesChoice.getItems().clear();
        seriesChoice.getItems().add(NO_SERIES);
        List<Series> all = manager().getSeries();
        for (int i = 0; i < all.size(); i++) {
            seriesChoice.getItems().add(all.get(i).getName());
        }
        seriesChoice.getItems().add(NEW_SERIES);
    }

    /**
     * E66: "New series..." asks for a name, adds the series, and selects it.
     * Cancelled or refused, the previous choice comes back.
     *
     * @param previous what was selected before "New series..."
     */
    private void createSeries(final String previous) {
        // Later: the dialog cannot open while the ComboBox is still
        // processing the selection that triggered it.
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                TextInputDialog dialog = new TextInputDialog();
                dialog.setTitle("New series");
                dialog.setHeaderText(null);
                dialog.setContentText("Series name:");
                dialog.initOwner(shell.getWindow());
                Optional<String> name = dialog.showAndWait();
                String back = previous == null ? NO_SERIES : previous;
                if (name.isPresent()) {
                    try {
                        Series series = new Series(name.get().trim());
                        manager().addSeries(series);
                        // A new series is a logical operation of its own (ADR 0001).
                        shell.saveChanges();
                        fillSeriesChoices();
                        seriesChoice.setValue(series.getName());
                        return;
                    } catch (IllegalArgumentException e) {
                        showBanner("error", e.getMessage());
                    }
                }
                seriesChoice.setValue(back);
            }
        });
    }

    private Series chosenSeries() {
        List<Series> all = manager().getSeries();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getName().equals(seriesChoice.getValue())) {
                return all.get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Save (E63, E64)
    // ------------------------------------------------------------------

    @FXML
    private void onSave() {
        save(false);
    }

    @FXML
    private void onSaveAnother() {
        save(true);
    }

    private void save(boolean addAnother) {
        stopScan();
        hideBanner();
        clearErrors();
        List<String> problems = new ArrayList<>();

        // 1. Validate everything against a scratch book; nothing real changes.
        String isbn13 = null;
        if (!isbnField.getText().trim().isEmpty()) {
            try {
                isbn13 = IsbnValidator.toIsbn13(isbnField.getText());
            } catch (InvalidISBNException e) {
                problem(problems, isbnField, e.getMessage());
            }
        }
        applyFields(new Book("scratch"), problems);
        if (!problems.isEmpty()) {
            showProblems(problems);
            return;
        }
        if (isbn13 != null && mode != Mode.QUEUE) {
            Book existing = manager().findBookByIsbn(isbn13);
            if (existing != null && existing != editing) {
                showDuplicate(existing);
                return;
            }
        }

        // 2. Every value passed: apply to the real target.
        try {
            if (mode == Mode.QUEUE) {
                // A queue row is not in the collection yet: nothing to save.
                editing.setIsbn(isbn13);
                applyFields(editing, problems);
                markClean();
                host.queueDone(editing, pendingCover);
            } else if (mode == Mode.EDIT) {
                manager().changeIsbn(editing, isbn13);
                applyFields(editing, problems);
                saved(editing, false);
            } else {
                Book book = new Book(titleField.getText().trim());
                book.setIsbn(isbn13);
                applyFields(book, problems);
                manager().addItem(book);
                saved(book, addAnother);
            }
        } catch (InvalidISBNException e) {
            problem(problems, isbnField, e.getMessage());
            showProblems(problems);
        } catch (DuplicateItemException e) {
            showDuplicate(manager().findBookByIsbn(e.getIsbn()));
        }
    }

    /**
     * The book is in the collection with every value applied: write its
     * cover, save (ADR 0001) and tell the Add page.
     */
    private void saved(Book book, boolean addAnother) {
        String coverProblem = keepCover(book);
        shell.saveChanges();
        markClean();
        shell.toast("Saved “" + book.getTitle() + "”" + (coverProblem == null ? "" : "  ·  " + coverProblem));
        host.saved(book, addAnother);
    }

    /**
     * E153: a new cover becomes the file {@code covers/<id>.jpg}; a removed
     * one is deleted. The book is saved even if this fails - a missing
     * cover is shown as a generated one, so nothing else breaks.
     *
     * @return null, or what went wrong, for the toast
     */
    private String keepCover(Book book) {
        try {
            if (pendingCover != null) {
                CoverArt.saveCover(book, dataFolder(), pendingCover);
            } else if (coverRemoved) {
                CoverArt.removeUpload(book, dataFolder());
            }
            return null;
        } catch (IOException e) {
            return "the cover could not be saved: " + e.getMessage();
        }
    }

    /**
     * Copies every control into a book, collecting a message for each value
     * the model refuses instead of stopping at the first.
     *
     * @param book     the scratch book (validation) or the real one
     * @param problems receives one line per refused value
     */
    private void applyFields(Book book, List<String> problems) {
        try {
            book.setTitle(titleField.getText().trim());
        } catch (IllegalArgumentException e) {
            problem(problems, titleField, e.getMessage());
        }
        book.setSubtitle(blankToNull(subtitleField.getText()));
        book.setPublisher(blankToNull(publisherField.getText()));
        book.setLanguage(blankToNull(languageField.getText()));
        int year = number(yearField, "Year", problems);
        if (year >= 0) {
            try {
                book.setPublicationYear(year);
            } catch (IllegalArgumentException e) {
                problem(problems, yearField, e.getMessage());
            }
        }
        int pages = number(pagesField, "Pages", problems);
        if (pages >= 0) {
            book.setPages(pages);
        }

        // Contributors: replaced wholesale by the rows, in screen order.
        List<Contributor> oldContributors = new ArrayList<>(book.getContributors());
        for (int i = 0; i < oldContributors.size(); i++) {
            book.removeContributor(oldContributors.get(i));
        }
        for (int i = 0; i < contributors.size(); i++) {
            ContributorRow row = contributors.get(i);
            if (!row.name.getText().trim().isEmpty()) {
                book.addContributor(new Contributor(row.name.getText().trim(), row.role.getValue()));
            }
        }

        Series series = chosenSeries();
        book.setSeriesId(series == null ? null : series.getId());
        int position = series == null ? 0 : number(positionField, "Series position", problems);
        book.setSeriesPosition(Math.max(position, 0));

        applyCopies(book, problems);

        List<String> oldIds = new ArrayList<>(book.getCategoryIds());
        for (int i = 0; i < oldIds.size(); i++) {
            book.removeCategoryId(oldIds.get(i));
        }
        for (int i = 0; i < chosenCategories.size(); i++) {
            book.addCategoryId(chosenCategories.get(i).getId());
        }

        applySessions(book, problems);
        // Set after the sessions, which adjust the status as they are added:
        // the dropdown is the user's final word.
        book.setReadingStatus(statusChoice.getValue());
        book.setRating(ratingChoice.getItems().indexOf(ratingChoice.getValue()));
        book.setNotes(blankToNull(notesArea.getText()));

        List<String> oldKeys = new ArrayList<>(book.getCustomFields().keySet());
        for (int i = 0; i < oldKeys.size(); i++) {
            book.removeCustomField(oldKeys.get(i));
        }
        for (int i = 0; i < fields.size(); i++) {
            FieldRow row = fields.get(i);
            if (row.key.getText().trim().isEmpty()) {
                continue;
            }
            try {
                book.setCustomField(row.key.getText().trim(), row.value.getText().trim());
            } catch (IllegalArgumentException e) {
                problem(problems, row.value, e.getMessage());
            }
        }
    }

    /**
     * Copies: a row that came from one of this book's copies updates it in
     * place (so its id survives); other rows become new copies; copies with
     * no row left are removed - last, so the book never has none.
     */
    private void applyCopies(Book book, List<String> problems) {
        if (copies.isEmpty()) {
            problem(problems, copyRows, "A book needs at least one copy. To get rid of it, move it to the Trash.");
            return;
        }
        List<Copy> kept = new ArrayList<>();
        for (int i = 0; i < copies.size(); i++) {
            CopyRow row = copies.get(i);
            boolean existing = row.original != null && book.getCopies().contains(row.original);
            Copy copy = existing ? row.original : new Copy();
            copy.setFormat(row.format.getValue());
            copy.setCondition(row.condition.getValue());
            manager().setLocation(copy, row.location);
            try {
                copy.setPurchaseDate(row.date.getValue());
            } catch (IllegalArgumentException e) {
                problem(problems, row.date, e.getMessage());
            }
            double price = money(row.price, problems);
            if (price >= 0) {
                copy.setPricePaid(price);
            }
            if (!existing) {
                book.addCopy(copy);
            }
            kept.add(copy);
        }
        List<Copy> all = new ArrayList<>(book.getCopies());
        for (int i = 0; i < all.size(); i++) {
            if (!kept.contains(all.get(i))) {
                book.removeCopy(all.get(i));
            }
        }
    }

    /** Sessions, in the same keep / add / remove way as copies. */
    private void applySessions(Book book, List<String> problems) {
        List<ReadingSession> kept = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            SessionRow row = sessions.get(i);
            if (row.start.getValue() == null) {
                problem(problems, row.start, "A reading session needs a start date.");
                continue;
            }
            boolean existing = row.original != null && book.getReadingSessions().contains(row.original);
            try {
                if (existing) {
                    ReadingSession s = row.original;
                    s.setStartDate(row.start.getValue());
                    if (!s.isFinished() && row.end.getValue() != null) {
                        // D13: closes it and marks the book Finished.
                        book.finishReadingSession(s, row.end.getValue());
                    } else {
                        s.setEndDate(row.end.getValue());
                    }
                    kept.add(s);
                } else {
                    ReadingSession s = new ReadingSession(row.start.getValue());
                    s.setEndDate(row.end.getValue());
                    book.addReadingSession(s);
                    kept.add(s);
                }
            } catch (IllegalArgumentException e) {
                problem(problems, row.end, e.getMessage());
            }
        }
        List<ReadingSession> all = new ArrayList<>(book.getReadingSessions());
        for (int i = 0; i < all.size(); i++) {
            if (!kept.contains(all.get(i))) {
                book.removeReadingSession(all.get(i));
            }
        }
    }

    /**
     * Reads a whole number from a field; blank means 0 ("not recorded").
     *
     * @return the number, or -1 after recording a problem
     */
    private int number(TextField field, String what, List<String> problems) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            problem(problems, field, what + " must be a whole number, got \"" + text + "\".");
            return -1;
        }
    }

    /** @return the amount, 0 if blank, or -1 after recording a problem */
    private double money(TextField field, List<String> problems) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(text);
            if (value < 0) {
                problem(problems, field, "Price cannot be negative, got " + text + ".");
                return -1;
            }
            return value;
        } catch (NumberFormatException e) {
            problem(problems, field, "Price must be a number, got \"" + text + "\".");
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Banner and field errors (E35, E63)
    // ------------------------------------------------------------------

    private void problem(List<String> problems, Node field, String message) {
        problems.add(message);
        markBad(field);
    }

    private void markBad(Node field) {
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
        badFields.add(field);
    }

    private void clearErrors() {
        for (int i = 0; i < badFields.size(); i++) {
            badFields.get(i).getStyleClass().remove("field-error");
        }
        badFields.clear();
    }

    /** E63: every problem in the banner, then the first bad field scrolled into view. */
    private void showProblems(List<String> problems) {
        String text = problems.size() == 1 ? "Can't save yet:" : "Can't save yet - " + problems.size() + " problems:";
        for (int i = 0; i < problems.size(); i++) {
            text += "\n• " + problems.get(i);
        }
        showBanner("error", text);
        if (!badFields.isEmpty()) {
            scrollTo(badFields.get(0));
            if (badFields.get(0) instanceof Control) {
                badFields.get(0).requestFocus();
            }
        }
    }

    /** Scrolls the form so a node sits near the top of the visible area. */
    private void scrollTo(Node node) {
        Node content = formScroll.getContent();
        if (!isInside(node, content)) {
            return;
        }
        Bounds inContent = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double hidden = content.getBoundsInLocal().getHeight() - formScroll.getViewportBounds().getHeight();
        if (hidden > 0) {
            formScroll.setVvalue(Math.max(0, Math.min(1, (inContent.getMinY() - 20) / hidden)));
        }
    }

    private static boolean isInside(Node node, Node container) {
        Node n = node;
        while (n != null) {
            if (n == container) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    /**
     * @param kind    "info", "warn" or "error", for the colour
     * @param message the text; may span lines
     * @param actions buttons shown at the banner's right end
     */
    private void showBanner(String kind, String message, Button... actions) {
        banner.getStyleClass().removeAll("info", "warn", "error");
        banner.getStyleClass().add(kind);
        bannerText.setText(message);
        bannerActions.getChildren().setAll(actions);
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void hideBanner() {
        banner.setVisible(false);
        banner.setManaged(false);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
