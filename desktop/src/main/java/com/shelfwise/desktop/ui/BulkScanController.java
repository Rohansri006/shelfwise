package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.metadata.LookupResult;
import com.shelfwise.core.metadata.MetadataLookup;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.service.CollectionManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.awt.Toolkit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Bulk scan. Barcodes from the camera, or ISBNs
 * pasted in, become rows in a review queue; each is looked up in the
 * background; Confirm all adds every row that is ready (E60).
 * <p>
 * <strong>Threads.</strong> Two kinds of background work, both kept off the
 * JavaFX thread: the camera ({@link CameraFeed}) and the lookups. Lookups
 * go through a <em>single-thread executor</em> - a queue served by one
 * worker thread - so scanning twenty books fires twenty lookups one after
 * another rather than twenty at once, which keyless catalogues would
 * answer with "too many requests". Results come back through
 * {@code Platform.runLater}.
 * <p>
 * Like the Single form, it is part of the Add page ({@link AddController}),
 * not a page of its own. Confirm all saves once for the whole batch
 * (ADR 0001).
 */
public class BulkScanController {

    /** What the scan pane tells the Add page. */
    interface Host {

        /** E37: open this row's draft in the Single form. */
        void editRow(Book draft, Image cover);
    }

    /** A row's state, with the words shown in the Status column. */
    enum Status {
        LOOKING_UP("Looking up…"),
        READY("Ready"),
        NOT_FOUND("Not found - fill in by hand"),
        DUPLICATE("Already in your collection"),
        IN_TRASH("In the Trash"),
        INVALID("Not a valid ISBN");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        String getLabel() {
            return label;
        }
    }

    /**
     * Title given to the draft of a book no catalogue knew. {@code Book}
     * requires a title, so the draft needs one; the form clears it, so the
     * user types the real one.
     */
    static final String UNTITLED = "Untitled";

    /** A repeat of the last code within this long is ignored even if its row was removed (E67). */
    private static final long COOLDOWN_MS = 3000;

    /** One scanned or pasted ISBN. */
    static class Row {
        String isbn;
        Status status;
        /** The book to add: from the lookup or the form; null until known. */
        Book draft;
        /** DUPLICATE / IN_TRASH: the book already in the collection. */
        Book existing;
        Image cover;
        /** NOT_FOUND: why, e.g. "Open Library: timed out"; shown as a tooltip. */
        String failure;
        /** Edited in the form: batch defaults no longer apply (E70). */
        boolean edited;
        /** DUPLICATE: add another copy. IN_TRASH: restore. Chosen by the user (D10). */
        boolean resolved;
        /** True for a moment after a repeat scan, so the row flashes (E67). */
        boolean flashing;
    }

    @FXML private StackPane previewFrame;
    @FXML private ImageView preview;
    @FXML private VBox noCamera;
    @FXML private Label noCameraText;
    @FXML private ComboBox<String> cameraChoice;
    @FXML private ToggleButton mirrorToggle;
    @FXML private Label cameraStatus;
    @FXML private TextArea pasteArea;
    @FXML private Button batchLocation;
    @FXML private FlowPane batchChips;
    @FXML private TableView<Row> queue;
    @FXML private Label summary;
    @FXML private Button removeSelected;
    @FXML private Button confirmAll;

    private Shell shell;
    private Session session;
    private Host host;
    private CameraFeed camera;

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private Location batchLoc;
    private final List<Category> batchCategories = new ArrayList<>();

    /** The row open in the form (E37), so Done knows where to write back. */
    private Row editingRow;

    private String lastCode;
    private long lastCodeAt;

    /** True while the code fills the camera list, so that does not count as the user picking one. */
    private boolean fillingCameras = false;

    /**
     * One worker thread that serves lookups in order. The thread factory
     * makes it a daemon, so it never keeps the program running after the
     * window closes.
     */
    private final ExecutorService lookups = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "bulk-lookup");
            thread.setDaemon(true);
            return thread;
        }
    });

    @FXML
    private void initialize() {
        camera = new CameraFeed(preview);
        // The picture follows the frame's width (less its border), so it
        // never pushes the left column into the queue.
        preview.fitWidthProperty().bind(previewFrame.widthProperty().subtract(8));
        applyMirror();
        cameraChoice.valueProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                if (!fillingCameras && now != null && old != null) {
                    startCamera();
                }
            }
        });
        setUpQueue();
        showBatchLocation();
        rebuildBatchChips();
        updateSummary();
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

    /** Asked for on every use, never kept: "Keep theirs" (E47) replaces it. */
    private CollectionManager manager() {
        return session.getManager();
    }

    /** The pane is shown: open the camera. */
    void activate() {
        startCamera();
    }

    /** The pane is hidden or the page left: release the camera (E59). */
    void deactivate() {
        camera.stop();
    }

    /**
     * Brings the queue up to date with the collection, each time the Add
     * page is shown. Meanwhile the user may have added one of the queued
     * books through the Single form, trashed or deleted one in Browse, or -
     * after "Keep theirs" (E47) - every book is a new object. So each row
     * finds its existing book again by ISBN, and the batch defaults find
     * their location and categories again by id, dropping any now gone.
     */
    void recheck() {
        CollectionManager manager = manager();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            // Invalid rows have no ISBN to check; a running lookup checks on Confirm.
            if (row.status == Status.INVALID || row.status == Status.LOOKING_UP) {
                continue;
            }
            Book existing = manager.findBookByIsbn(row.isbn);
            if (existing != null) {
                Status now = existing.isDeleted() ? Status.IN_TRASH : Status.DUPLICATE;
                if (row.status != now) {
                    // A new situation needs a new decision (D10).
                    row.status = now;
                    row.resolved = false;
                }
                row.existing = existing;
            } else if (row.existing != null) {
                // The book it matched is gone: treat the ISBN as new.
                row.existing = null;
                row.resolved = false;
                if (row.draft != null) {
                    row.status = Status.READY;
                } else {
                    row.status = Status.LOOKING_UP;
                    lookUp(row);
                }
            }
        }
        if (batchLoc != null) {
            batchLoc = manager.findLocationById(batchLoc.getId());
        }
        List<Category> kept = new ArrayList<>();
        for (int i = 0; i < batchCategories.size(); i++) {
            Category now = manager.findCategoryById(batchCategories.get(i).getId());
            if (now != null) {
                kept.add(now);
            }
        }
        batchCategories.clear();
        batchCategories.addAll(kept);
        showBatchLocation();
        rebuildBatchChips();
        queue.refresh();
        updateSummary();
    }

    // ------------------------------------------------------------------
    // Camera (E38, E59, E68)
    // ------------------------------------------------------------------

    private void startCamera() {
        noCamera.setVisible(false);
        preview.setVisible(true);
        cameraStatus.setText("Opening the camera…");
        int index = Math.max(0, cameraChoice.getSelectionModel().getSelectedIndex());
        camera.start(index, new CameraFeed.Listener() {
            @Override
            public void devices(List<String> names) {
                fillingCameras = true;
                String current = cameraChoice.getValue();
                cameraChoice.getItems().setAll(names);
                if (current != null && names.contains(current)) {
                    cameraChoice.setValue(current);
                } else if (!names.isEmpty()) {
                    cameraChoice.setValue(names.get(0));
                }
                fillingCameras = false;
            }

            @Override
            public void live(String name) {
                cameraStatus.setText("Camera live. Hold a barcode up to it.");
            }

            @Override
            public void code(String code) {
                scanned(code);
            }

            @Override
            public void failed(String message) {
                showNoCamera(message + "\nYou can still paste ISBNs below.");
            }
        });
    }

    private void showNoCamera(String message) {
        preview.setVisible(false);
        noCamera.setVisible(true);
        noCameraText.setText(message);
        cameraStatus.setText("");
    }

    @FXML
    private void onRetry() {
        startCamera();
    }

    @FXML
    private void onMirror() {
        applyMirror();
    }

    /**
     * Display only: a negative horizontal scale flips the picture so moving
     * a book right moves it right on screen. The frames given to the decoder
     * are never flipped, so reading is unaffected.
     */
    private void applyMirror() {
        preview.setScaleX(mirrorToggle.isSelected() ? -1 : 1);
    }

    /** A barcode from the camera. Codes that are not ISBNs are ignored quietly. */
    private void scanned(String code) {
        long now = System.currentTimeMillis();
        // E67: one book held in front of the camera is read ~8 times a second.
        if (code.equals(lastCode) && now - lastCodeAt < COOLDOWN_MS) {
            lastCodeAt = now;
            return;
        }
        lastCode = code;
        lastCodeAt = now;
        if (!IsbnValidator.isValid(code)) {
            cameraStatus.setText("Read " + code + " - not a book barcode.");
            return;
        }
        if (enqueue(code)) {
            feedback();
        }
    }

    /** E68: the preview border flashes and the system beeps. */
    private void feedback() {
        previewFrame.getStyleClass().add("flash");
        Toolkit.getDefaultToolkit().beep();
        PauseTransition off = new PauseTransition(Duration.millis(400));
        off.setOnFinished(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                previewFrame.getStyleClass().remove("flash");
            }
        });
        off.play();
    }

    // ------------------------------------------------------------------
    // Paste box and queue
    // ------------------------------------------------------------------

    @FXML
    private void onPaste() {
        String[] lines = pasteArea.getText().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                enqueue(line);
            }
        }
        pasteArea.clear();
    }

    /**
     * Adds an ISBN to the queue and starts its lookup.
     *
     * @param raw the ISBN as scanned or typed
     * @return true if a row was added; false for a repeat (E67)
     */
    boolean enqueue(String raw) {
        Row row = new Row();
        try {
            row.isbn = IsbnValidator.toIsbn13(raw);
        } catch (InvalidISBNException e) {
            row.isbn = raw;
            row.status = Status.INVALID;
            rows.add(row);
            updateSummary();
            return true;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (row.isbn.equals(rows.get(i).isbn)) {
                flash(rows.get(i));
                return false;
            }
        }
        // D10: an ISBN already owned, live or trashed, needs a decision.
        Book existing = manager().findBookByIsbn(row.isbn);
        if (existing != null) {
            row.existing = existing;
            row.status = existing.isDeleted() ? Status.IN_TRASH : Status.DUPLICATE;
        } else {
            row.status = Status.LOOKING_UP;
            lookUp(row);
        }
        rows.add(row);
        queue.scrollTo(row);
        updateSummary();
        return true;
    }

    /**
     * Queues one lookup on the single worker thread.
     * <p>
     * Round-2 fix: the row turns Ready as soon as the details arrive; its
     * cover is fetched as a <em>separate</em> task afterwards. The cover
     * used to be part of the lookup, so one slow cover held up every row
     * queued behind it.
     */
    private void lookUp(final Row row) {
        // Built here, on the JavaFX thread, which is where settings are read.
        final MetadataLookup lookup = session.newLookup();
        lookups.submit(new Runnable() {
            @Override
            public void run() {
                Book found = null;
                String coverUrl = null;
                String reason = null;
                try {
                    LookupResult result = lookup.lookUp(row.isbn);
                    found = result.toBook(null);
                    coverUrl = result.getMetadata().getCoverUrl();
                } catch (Exception e) {
                    // Not found, or offline: the row asks to be filled in by hand,
                    // and keeps the reason so the user can see why.
                    reason = e.getMessage();
                }
                final Book book = found;
                final String failure = reason;
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        if (row.edited) {
                            return; // the user filled it in by hand meanwhile
                        }
                        row.draft = book;
                        row.failure = failure;
                        row.status = book == null ? Status.NOT_FOUND : Status.READY;
                        queue.refresh();
                        updateSummary();
                    }
                });
                if (coverUrl != null) {
                    fetchCover(row, coverUrl);
                }
            }
        });
    }

    /**
     * Downloads a row's cover on the same worker, queued after any lookups
     * already waiting, so details always come first. {@link CoverArt#download}
     * gives up after 5 s, so even a stalled server only delays the queue
     * that long.
     */
    private void fetchCover(final Row row, final String url) {
        lookups.submit(new Runnable() {
            @Override
            public void run() {
                final Image image = CoverArt.download(url);
                if (image == null) {
                    return; // the generated cover stays
                }
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        // A cover chosen in the form while this downloaded wins.
                        if (row.cover == null) {
                            row.cover = image;
                            queue.refresh();
                        }
                    }
                });
            }
        });
    }

    /** E67: a repeat scan makes the existing row flash for a moment. */
    private void flash(final Row row) {
        row.flashing = true;
        queue.refresh();
        queue.scrollTo(row);
        PauseTransition off = new PauseTransition(Duration.millis(700));
        off.setOnFinished(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                row.flashing = false;
                queue.refresh();
            }
        });
        off.play();
    }

    /**
     * E37: the form's Done wrote the edited draft back.
     *
     * @param draft the edited book
     * @param cover its cover, or null
     */
    void rowEdited(Book draft, Image cover) {
        if (editingRow == null) {
            return;
        }
        editingRow.draft = draft;
        editingRow.cover = cover;
        editingRow.edited = true;
        // The ISBN may have been corrected in the form.
        editingRow.isbn = draft.getIsbn() == null ? editingRow.isbn : draft.getIsbn();
        editingRow.status = Status.READY;
        editingRow = null;
        queue.refresh();
        updateSummary();
    }

    private void editRow(Row row) {
        editingRow = row;
        Book draft = row.draft;
        if (draft == null) {
            draft = new Book(UNTITLED);
            try {
                draft.setIsbn(row.status == Status.INVALID ? null : row.isbn);
            } catch (InvalidISBNException e) {
                // Cannot happen: only valid ISBNs reach a row that is not INVALID.
            }
        }
        host.editRow(draft, row.cover);
    }

    /** The row's × or Skip: the same confirmed removal as a selection of one (E87). */
    private void removeRow(Row row) {
        List<Row> one = new ArrayList<>();
        one.add(row);
        removeRows(one);
    }

    /** E86: "Remove selected", or Delete / Backspace on the queue. */
    @FXML
    private void onRemoveSelected() {
        removeRows(new ArrayList<>(queue.getSelectionModel().getSelectedItems()));
    }

    /**
     * E87: every removal asks first, even of one row - a scanned row can
     * hold edits made in the form, which would be lost with it.
     */
    private void removeRows(List<Row> doomed) {
        if (doomed.isEmpty()) {
            return;
        }
        ButtonType remove = new ButtonType("Remove");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, removeMessage(doomed), ButtonType.CANCEL, remove);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isPresent() && answer.get() == remove) {
            dropRows(doomed);
        }
    }

    /** Takes rows out of the queue, without asking. */
    private void dropRows(List<Row> doomed) {
        rows.removeAll(doomed);
        updateSummary();
    }

    /** E87: names the book for one row, counts several. */
    static String removeMessage(List<Row> doomed) {
        if (doomed.size() == 1) {
            return "Remove “" + rowName(doomed.get(0)) + "” from the queue?";
        }
        return "Remove " + doomed.size() + " rows from the queue?";
    }

    /** @return the row's title if known, else its ISBN as scanned */
    private static String rowName(Row row) {
        Book shown = row.draft != null ? row.draft : row.existing;
        return shown == null || UNTITLED.equals(shown.getTitle()) ? row.isbn : shown.getTitle();
    }

    /** E83: Shortcut+A selects every row in the queue. */
    void selectAll() {
        queue.getSelectionModel().selectAll();
    }

    /**
     * E88: Esc clears a selection first.
     *
     * @return true if there was a selection to clear
     */
    boolean clearSelection() {
        if (queue.getSelectionModel().getSelectedItems().isEmpty()) {
            return false;
        }
        queue.getSelectionModel().clearSelection();
        return true;
    }

    /** @return true if Confirm all will act on this row */
    private static boolean isReady(Row row) {
        return row.status == Status.READY
                || ((row.status == Status.DUPLICATE || row.status == Status.IN_TRASH) && row.resolved);
    }

    private void updateSummary() {
        int ready = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (isReady(rows.get(i))) {
                ready++;
            }
        }
        int attention = rows.size() - ready;
        summary.setText(rows.isEmpty() ? "Scan or paste ISBNs to fill the queue."
                : ready + " ready" + (attention == 0 ? "" : "  ·  " + attention + " need attention"));
        confirmAll.setDisable(ready == 0);
    }

    // ------------------------------------------------------------------
    // Batch defaults (E70)
    // ------------------------------------------------------------------

    @FXML
    private void onBatchLocation() {
        new TreePicker<Location>(new LocationTree(session, shell), "location", "Don't set a location",
                new TreePicker.Chosen<Location>() {
                    @Override
                    public void chosen(Location node) {
                        batchLoc = node;
                        showBatchLocation();
                    }
                }).showBelow(batchLocation);
    }

    private void showBatchLocation() {
        batchLocation.setText(batchLoc == null ? "Location: not set" : batchLoc.getPath());
    }

    private void rebuildBatchChips() {
        batchChips.getChildren().clear();
        for (int i = 0; i < batchCategories.size(); i++) {
            final Category c = batchCategories.get(i);
            batchChips.getChildren().add(BrowseController.chip(c.getName(), new Runnable() {
                @Override
                public void run() {
                    batchCategories.remove(c);
                    rebuildBatchChips();
                }
            }));
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
                                if (node != null && !batchCategories.contains(node)) {
                                    batchCategories.add(node);
                                    rebuildBatchChips();
                                }
                            }
                        }).showBelow(add);
            }
        });
        batchChips.getChildren().add(add);
    }

    // ------------------------------------------------------------------
    // Confirm all (E60)
    // ------------------------------------------------------------------

    @FXML
    private void onConfirmAll() {
        int added = 0;
        int copies = 0;
        int restored = 0;
        int coversLost = 0;
        CollectionManager manager = manager();
        // A copy of the list: rows are removed from the real one as they go.
        List<Row> all = new ArrayList<>(rows);
        for (int i = 0; i < all.size(); i++) {
            Row row = all.get(i);
            if (!isReady(row)) {
                continue;
            }
            if (row.status == Status.READY) {
                Book book = row.draft;
                if (!row.edited) {
                    applyBatch(book);
                }
                try {
                    manager.addItem(book);
                } catch (DuplicateItemException e) {
                    // Added meanwhile (e.g. through the Single form): ask again.
                    row.existing = manager.findBookByIsbn(e.getIsbn());
                    row.status = row.existing.isDeleted() ? Status.IN_TRASH : Status.DUPLICATE;
                    row.resolved = false;
                    continue;
                }
                if (row.cover != null && !saveCover(book, row.cover)) {
                    coversLost++;
                }
                added++;
            } else if (row.status == Status.DUPLICATE) {
                Copy copy = new Copy();
                manager.setLocation(copy, batchLoc);
                row.existing.addCopy(copy);
                copies++;
            } else {
                manager.restoreFromTrash(row.existing.getId());
                restored++;
            }
            rows.remove(row);
        }
        queue.refresh();
        updateSummary();
        String message = confirmMessage(added, copies, restored);
        if (message.isEmpty()) {
            return; // every ready row turned out to be a duplicate: nothing changed
        }
        // One save for the whole batch: Confirm all is one logical operation.
        shell.saveChanges();
        if (coversLost > 0) {
            message += "  ·  " + coversLost + (coversLost == 1 ? " cover" : " covers") + " could not be saved";
        }
        shell.toast(message);
    }

    /**
     * E153: writes a confirmed row's cover to {@code covers/}.
     *
     * @return false if the file could not be written; the book is kept anyway
     */
    private boolean saveCover(Book book, Image cover) {
        try {
            CoverArt.saveCover(book, session.getDataFolder(), cover);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The toast after Confirm all, e.g. "3 books added, 1 copy added".
     *
     * @return the message, or "" if nothing was done
     */
    static String confirmMessage(int added, int copies, int restored) {
        List<String> parts = new ArrayList<>();
        if (added > 0) {
            parts.add(added + (added == 1 ? " book added" : " books added"));
        }
        if (copies > 0) {
            parts.add(copies + (copies == 1 ? " copy added" : " copies added"));
        }
        if (restored > 0) {
            parts.add(restored + " restored");
        }
        return String.join(", ", parts);
    }

    /** E70: the batch location goes on every copy, the batch categories are added. */
    private void applyBatch(Book book) {
        if (batchLoc != null) {
            for (int i = 0; i < book.getCopies().size(); i++) {
                manager().setLocation(book.getCopies().get(i), batchLoc);
            }
        }
        for (int i = 0; i < batchCategories.size(); i++) {
            book.addCategoryId(batchCategories.get(i).getId());
        }
    }

    // ------------------------------------------------------------------
    // The queue table
    // ------------------------------------------------------------------

    /**
     * Every column's value is the whole {@link Row}; each column's cell
     * factory decides what to draw from it. That keeps one source of truth
     * per row instead of a property per column.
     */
    private void setUpQueue() {
        queue.setItems(rows);
        // E86: several rows can be selected and removed together.
        queue.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        removeSelected.setDisable(true);
        queue.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<Row>() {
            @Override
            public void onChanged(Change<? extends Row> change) {
                removeSelected.setDisable(queue.getSelectionModel().getSelectedItems().isEmpty());
            }
        });
        // Delete or Backspace: Mac keyboards label Backspace "delete".
        queue.addEventHandler(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                    onRemoveSelected();
                    event.consume();
                }
            }
        });
        queue.setPlaceholder(new Label("Nothing scanned yet."));
        queue.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        queue.getColumns().add(rowColumn("", 52, new CellPainter() {
            @Override
            public Node paint(Row row) {
                Book shown = row.draft != null ? row.draft : row.existing;
                if (row.cover != null) {
                    return CoverArt.imageView(row.cover, 38, 54);
                }
                if (row.existing != null) {
                    return CoverArt.coverFor(row.existing, session.getDataFolder(), 38, 54, false);
                }
                return CoverArt.generated(shown != null ? shown : new Book(row.isbn), 38, 54);
            }
        }));
        queue.getColumns().add(rowColumn("Book", 260, new CellPainter() {
            @Override
            public Node paint(Row row) {
                Book shown = row.draft != null ? row.draft : row.existing;
                Label title = new Label(shown == null ? row.isbn : shown.getTitle());
                title.getStyleClass().add("card-title");
                Contributor author = shown == null ? null : shown.getPrimaryAuthor();
                Label sub = new Label((author == null ? "" : author.getName() + "  ·  ") + row.isbn);
                sub.getStyleClass().add("muted");
                return new VBox(2, title, sub);
            }
        }));
        queue.getColumns().add(rowColumn("Status", 190, new CellPainter() {
            @Override
            public Node paint(Row row) {
                Label status = new Label(statusText(row));
                status.getStyleClass().addAll("queue-status", "queue-" + (isReady(row) ? "ready" : "attention"));
                status.setWrapText(true);
                if (row.status == Status.NOT_FOUND && row.failure != null) {
                    status.setTooltip(new Tooltip(row.failure));
                }
                return status;
            }
        }));
        queue.getColumns().add(rowColumn("", 220, new CellPainter() {
            @Override
            public Node paint(Row row) {
                return actions(row);
            }
        }));

        // E67: rows flagged as flashing get a style class for a moment.
        queue.setRowFactory(new Callback<TableView<Row>, TableRow<Row>>() {
            @Override
            public TableRow<Row> call(TableView<Row> view) {
                return new TableRow<Row>() {
                    @Override
                    protected void updateItem(Row item, boolean empty) {
                        super.updateItem(item, empty);
                        getStyleClass().remove("flash");
                        if (!empty && item != null && item.flashing) {
                            getStyleClass().add("flash");
                        }
                    }
                };
            }
        });
    }

    private static String statusText(Row row) {
        if (row.status == Status.DUPLICATE && row.resolved) {
            return "Will add another copy";
        }
        if (row.status == Status.IN_TRASH && row.resolved) {
            return "Will restore from the Trash";
        }
        if (row.status == Status.READY && row.edited) {
            return "Ready (edited)";
        }
        return row.status.getLabel();
    }

    /** The buttons a row offers, which depend on its state (D10). */
    private HBox actions(final Row row) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_RIGHT);
        if (row.status == Status.DUPLICATE || row.status == Status.IN_TRASH) {
            String yes = row.status == Status.DUPLICATE ? "Add as copy" : "Restore";
            final Button choose = new Button(row.resolved ? "Undo" : yes);
            choose.getStyleClass().add(row.resolved ? "plain-button" : "accent-button");
            choose.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    row.resolved = !row.resolved;
                    queue.refresh();
                    updateSummary();
                }
            });
            box.getChildren().add(choose);
            box.getChildren().add(textButton("Skip", new Runnable() {
                @Override
                public void run() {
                    removeRow(row);
                }
            }));
            return box;
        }
        if (row.status != Status.LOOKING_UP) {
            box.getChildren().add(iconButton("mdi2p-pencil", "Edit", new Runnable() {
                @Override
                public void run() {
                    editRow(row);
                }
            }));
        }
        box.getChildren().add(iconButton("mdi2c-close", "Remove", new Runnable() {
            @Override
            public void run() {
                removeRow(row);
            }
        }));
        return box;
    }

    private static Button textButton(String text, final Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("plain-button");
        button.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
        return button;
    }

    private static Button iconButton(String icon, String tip, final Runnable action) {
        Button button = new Button();
        button.getStyleClass().add("icon-button");
        button.setGraphic(new FontIcon(icon));
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
        return button;
    }

    /** Draws one cell from a whole row. */
    private interface CellPainter {
        Node paint(Row row);
    }

    private static TableColumn<Row, Row> rowColumn(String name, double width, final CellPainter painter) {
        TableColumn<Row, Row> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<Row, Row>, ObservableValue<Row>>() {
            @Override
            public ObservableValue<Row> call(TableColumn.CellDataFeatures<Row, Row> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue());
            }
        });
        column.setCellFactory(new Callback<TableColumn<Row, Row>, TableCell<Row, Row>>() {
            @Override
            public TableCell<Row, Row> call(TableColumn<Row, Row> col) {
                return new TableCell<Row, Row>() {
                    @Override
                    protected void updateItem(Row item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(null);
                        setGraphic(empty || item == null ? null : painter.paint(item));
                    }
                };
            }
        });
        return column;
    }
}
