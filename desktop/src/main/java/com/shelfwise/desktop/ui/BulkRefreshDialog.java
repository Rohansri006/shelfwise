package com.shelfwise.desktop.ui;

import com.shelfwise.core.metadata.MetadataLookup;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.IsbnValidator;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Refresh details for many books at once (E146).
 * One dialog, three steps:
 * <ol>
 *   <li><b>Rules</b>: per field, Fill if empty / Replace / Keep.</li>
 *   <li><b>Looking up</b>: one book after another on a background thread,
 *       with a progress bar. The cache is tried first, so cached books
 *       need no network.</li>
 *   <li><b>Review</b>: every proposed change, grouped by book, each with a
 *       tick; unticking a book unticks all its changes. Books not found or
 *       without an ISBN are listed, not silently dropped (E148).</li>
 * </ol>
 * Shown with {@code show()} and reporting through {@link RefreshDialog.Done},
 * like the single review, so the window never blocks while books are looked up.
 */
final class BulkRefreshDialog {

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final List<Book> books;
    private final MetadataLookup lookup;
    private final Path dataFolder;
    private final Map<RefreshDetails.Field, ComboBox<RefreshDetails.Rule>> ruleBoxes =
            new EnumMap<>(RefreshDetails.Field.class);
    private final Map<RefreshDetails.Field, RefreshDetails.Rule> rules =
            new EnumMap<>(RefreshDetails.Field.class);

    private final ButtonType lookUpType = new ButtonType("Look up", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType applyType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);

    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressText = new Label();
    private final Label summary = new Label();

    /** Found answers, one slot per book (null = not looked up / failed). */
    private final RefreshDetails.Found[] found;
    private final List<String> notFound = new ArrayList<>();
    private final List<String> noIsbn = new ArrayList<>();
    private final List<RefreshDetails.Change> changes = new ArrayList<>();
    private TreeView<String> tree;

    /**
     * Set by Cancel, read by the worker thread between books. {@code volatile}
     * makes a write on the JavaFX thread visible to the worker at once;
     * without it the worker could keep reading an old cached value.
     */
    private volatile boolean cancelled;

    private BulkRefreshDialog(List<Book> books, MetadataLookup lookup, Path dataFolder) {
        this.books = new ArrayList<>(books);
        this.lookup = lookup;
        this.dataFolder = dataFolder;
        this.found = new RefreshDetails.Found[books.size()];
    }

    /**
     * @param owner  the main window: the dialog's owner, and its theme
     * @param books  the books to refresh (the Browse selection, or the
     *               books with unknown pages from Stats)
     * @param preset the rule each field starts with
     * @param done   told the ticked changes on Apply; not called on Cancel
     * @return the open dialog's controller
     */
    static BulkRefreshDialog open(Window owner, List<Book> books, MetadataLookup lookup, Path dataFolder,
                                  Map<RefreshDetails.Field, RefreshDetails.Rule> preset,
                                  RefreshDialog.Done done) {
        BulkRefreshDialog bulk = new BulkRefreshDialog(books, lookup, dataFolder);
        bulk.build(owner, preset, done);
        return bulk;
    }

    /** The rule every field starts with: Fill if empty, so nothing of yours is overwritten (E146). */
    static Map<RefreshDetails.Field, RefreshDetails.Rule> fillEmptyEverywhere() {
        Map<RefreshDetails.Field, RefreshDetails.Rule> map = new EnumMap<>(RefreshDetails.Field.class);
        RefreshDetails.Field[] fields = RefreshDetails.Field.values();
        for (int i = 0; i < fields.length; i++) {
            map.put(fields[i], RefreshDetails.Rule.FILL_EMPTY);
        }
        return map;
    }

    /** E138: Stats' "Fill…" link - pages only, every other field kept. */
    static Map<RefreshDetails.Field, RefreshDetails.Rule> pagesOnly() {
        Map<RefreshDetails.Field, RefreshDetails.Rule> map = new EnumMap<>(RefreshDetails.Field.class);
        RefreshDetails.Field[] fields = RefreshDetails.Field.values();
        for (int i = 0; i < fields.length; i++) {
            map.put(fields[i], RefreshDetails.Rule.KEEP);
        }
        map.put(RefreshDetails.Field.PAGES, RefreshDetails.Rule.FILL_EMPTY);
        return map;
    }

    // ------------------------------------------------------------------
    // Step 1: rules
    // ------------------------------------------------------------------

    private void build(Window owner, Map<RefreshDetails.Field, RefreshDetails.Rule> preset,
                       final RefreshDialog.Done done) {
        dialog.setTitle("Refresh details");
        dialog.initOwner(owner);
        // The window's theme, so the dialog is dark in dark mode too.
        dialog.getDialogPane().getStylesheets().setAll(owner.getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().addAll("add-page", "refresh-dialog");
        dialog.getDialogPane().setPrefWidth(560);

        Label heading = new Label("Refresh details for " + WishlistPicker.count(books.size(), "book", "books"));
        heading.getStyleClass().add("section-title");
        Label hint = new Label("For each field, choose what to do with the value found online. "
                + "You review every change before anything is applied.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(8);
        RefreshDetails.Field[] fields = RefreshDetails.Field.values();
        for (int i = 0; i < fields.length; i++) {
            ComboBox<RefreshDetails.Rule> box = new ComboBox<>();
            box.getItems().addAll(RefreshDetails.Rule.values());
            box.setValue(preset.get(fields[i]));
            box.getStyleClass().add("combo");
            ruleBoxes.put(fields[i], box);
            grid.add(new Label(fields[i].getLabel()), 0, i);
            grid.add(box, 1, i);
        }
        VBox content = new VBox(10, heading, hint, grid);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);

        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, lookUpType);
        Button lookUpButton = (Button) dialog.getDialogPane().lookupButton(lookUpType);
        lookUpButton.getStyleClass().add("accent-button");
        // A filter runs before the dialog would close; consuming keeps it open for step 2.
        lookUpButton.addEventFilter(ActionEvent.ACTION, new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                event.consume();
                startLookups();
            }
        });

        dialog.setOnHidden(new EventHandler<DialogEvent>() {
            @Override
            public void handle(DialogEvent event) {
                // Cancel (or closing the window) also stops a lookup in progress.
                cancelled = true;
                if (dialog.getResult() == applyType) {
                    List<RefreshDetails.Change> chosen = new ArrayList<>();
                    for (int i = 0; i < changes.size(); i++) {
                        if (changes.get(i).use) {
                            chosen.add(changes.get(i));
                        }
                    }
                    done.apply(chosen);
                }
            }
        });
        dialog.show();
    }

    // ------------------------------------------------------------------
    // Step 2: looking up
    // ------------------------------------------------------------------

    private void startLookups() {
        RefreshDetails.Field[] fields = RefreshDetails.Field.values();
        for (int i = 0; i < fields.length; i++) {
            rules.put(fields[i], ruleBoxes.get(fields[i]).getValue());
        }
        // Everything the worker needs is read here, on the JavaFX thread:
        // the books belong to the screen and must not be read from two threads.
        final String[] isbns = new String[books.size()];
        final boolean[] wantCover = new boolean[books.size()];
        RefreshDetails.Rule coverRule = rules.get(RefreshDetails.Field.COVER);
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            isbns[i] = IsbnValidator.isValid(book.getIsbn()) ? book.getIsbn() : null;
            // A cover is downloaded only if the rule could use it: the slow part of a lookup.
            wantCover[i] = coverRule == RefreshDetails.Rule.REPLACE
                    || (coverRule == RefreshDetails.Rule.FILL_EMPTY && !RefreshDetails.hasCover(book, dataFolder));
        }

        dialog.getDialogPane().getButtonTypes().remove(lookUpType);
        progressText.setText("Looking up 1 of " + books.size() + "…");
        progress.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10, progressText, progress);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < isbns.length && !cancelled; i++) {
                    RefreshDetails.Found answer = null;
                    String problem = null;
                    if (isbns[i] == null) {
                        problem = "no ISBN";
                    } else {
                        try {
                            answer = RefreshDetails.lookUp(lookup, isbns[i], wantCover[i]);
                        } catch (Exception e) {
                            problem = e.getMessage();
                        }
                    }
                    final int index = i;
                    final RefreshDetails.Found result = answer;
                    final String why = problem;
                    // Only runLater touches the screen and the shared lists.
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            recordResult(index, result, why);
                        }
                    });
                }
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        if (!cancelled) {
                            showReview();
                        }
                    }
                });
            }
        }, "bulk-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void recordResult(int index, RefreshDetails.Found result, String why) {
        Book book = books.get(index);
        if (result != null) {
            found[index] = result;
        } else if ("no ISBN".equals(why)) {
            noIsbn.add(book.getTitle());
        } else {
            notFound.add(book.getTitle());
        }
        int done = index + 1;
        progress.setProgress((double) done / books.size());
        progressText.setText(done < books.size() ? "Looking up " + (done + 1) + " of " + books.size() + "…"
                : "Comparing…");
    }

    // ------------------------------------------------------------------
    // Step 3: review
    // ------------------------------------------------------------------

    private void showReview() {
        CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("All changes");
        root.setExpanded(true);
        int changedBooks = 0;
        int unchanged = 0;
        for (int i = 0; i < books.size(); i++) {
            if (found[i] == null) {
                continue;
            }
            Book book = books.get(i);
            List<RefreshDetails.Change> forBook = RefreshDetails.diff(book, RefreshDetails.values(book),
                    RefreshDetails.hasCover(book, dataFolder), found[i], rules);
            if (forBook.isEmpty()) {
                unchanged++;
                continue;
            }
            changedBooks++;
            CheckBoxTreeItem<String> bookItem = new CheckBoxTreeItem<>(book.getTitle());
            bookItem.setExpanded(true);
            for (int j = 0; j < forBook.size(); j++) {
                final RefreshDetails.Change change = forBook.get(j);
                changes.add(change);
                CheckBoxTreeItem<String> leaf = new CheckBoxTreeItem<>(change.describe(), null, true);
                // Ticking or unticking the book's row ticks its leaves too
                // (CheckBoxTreeItem does that), and each leaf reports here.
                leaf.selectedProperty().addListener(new ChangeListener<Boolean>() {
                    @Override
                    public void changed(ObservableValue<? extends Boolean> obs, Boolean was, Boolean now) {
                        change.use = now;
                        updateApply();
                    }
                });
                bookItem.getChildren().add(leaf);
            }
            // The book's own box starts ticked like its rows, so unticking it
            // is a real change and passes down to every row.
            bookItem.setSelected(true);
            root.getChildren().add(bookItem);
        }

        tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.setCellFactory(CheckBoxTreeCell.<String>forTreeView());
        tree.setPrefHeight(320);
        tree.getStyleClass().add("refresh-tree");

        summary.setText(WishlistPicker.count(changedBooks, "book", "books") + " to update  ·  "
                + WishlistPicker.count(changes.size(), "change", "changes")
                + (unchanged > 0 ? "  ·  " + unchanged + " already up to date" : ""));
        VBox content = new VBox(10, summary);
        if (changes.isEmpty()) {
            Label nothing = new Label("Nothing to change with these rules.");
            nothing.getStyleClass().add("muted");
            content.getChildren().add(nothing);
        } else {
            content.getChildren().add(tree);
        }
        if (!notFound.isEmpty()) {
            content.getChildren().add(skipped("Not found (" + notFound.size() + "): ", notFound));
        }
        if (!noIsbn.isEmpty()) {
            content.getChildren().add(skipped("No ISBN (" + noIsbn.size() + "): ", noIsbn));
        }
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);

        dialog.getDialogPane().getButtonTypes().add(applyType);
        ((Button) dialog.getDialogPane().lookupButton(applyType)).getStyleClass().add("accent-button");
        updateApply();
    }

    private static Label skipped(String prefix, List<String> titles) {
        Label label = new Label(prefix + String.join(", ", titles));
        label.getStyleClass().add("muted");
        label.setWrapText(true);
        return label;
    }

    private void updateApply() {
        Button apply = (Button) dialog.getDialogPane().lookupButton(applyType);
        if (apply == null) {
            return;
        }
        int n = RefreshDetails.countUsed(changes);
        apply.setText("Apply " + WishlistPicker.count(n, "change", "changes"));
        apply.setDisable(n == 0);
    }
}
