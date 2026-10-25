package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.IsbnValidator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Refresh details as Browse and Stats run it (E142-E148): look up, review,
 * apply to the books, save, and offer Undo. Browse uses it from the details
 * panel and the bulk bar, Stats from the Pages tile's "Fill…", so the flow
 * lives here once rather than in both pages.
 * <p>
 * The Edit form does not use this class: its review fills the form only,
 * and nothing is saved until the user presses Save (E145).
 * <p>
 * Each call takes a {@code redraw} Runnable - the calling page's way of
 * showing the changed books again - so this class never needs to know which
 * page it is working for.
 */
final class RefreshFlow {

    private final Session session;
    private final Shell shell;

    /**
     * @param session the open collection
     * @param shell   the window, for saving and toasts
     */
    RefreshFlow(Session session, Shell shell) {
        this.session = session;
        this.shell = shell;
    }

    /**
     * E142/E145 for one book: looks it up on a background thread, then shows
     * the single review. The button shows "Looking up…" meanwhile.
     *
     * @param book   the book to refresh
     * @param button the button pressed; it is disabled during the lookup
     * @param redraw shows the changed book again after Apply or Undo
     */
    void refreshOne(final Book book, final Button button, final Runnable redraw) {
        if (!IsbnValidator.isValid(book.getIsbn())) {
            shell.toast("‘" + book.getTitle() + "’ has no ISBN to look up");
            return;
        }
        // Read on the JavaFX thread: the book belongs to the screen.
        final String isbn = book.getIsbn();
        final boolean hasCover = RefreshDetails.hasCover(book, session.getDataFolder());
        final String label = button.getText();
        button.setDisable(true);
        button.setText("Looking up…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final RefreshDetails.Found found = RefreshDetails.lookUp(session.newLookup(), isbn, true);
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            button.setDisable(false);
                            button.setText(label);
                            review(book, found, hasCover, redraw);
                        }
                    });
                } catch (final Exception e) {
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            button.setDisable(false);
                            button.setText(label);
                            shell.toast("Not found online: " + e.getMessage());
                        }
                    });
                }
            }
        }, "refresh-details");
        // A daemon thread never keeps the app from quitting.
        worker.setDaemon(true);
        worker.start();
    }

    /** E145: the single review, or a toast when nothing differs. */
    private void review(final Book book, RefreshDetails.Found found, boolean hasCover, final Runnable redraw) {
        List<RefreshDetails.Change> changes = RefreshDetails.diff(book, RefreshDetails.values(book), hasCover,
                found, null);
        if (changes.isEmpty()) {
            shell.toast("‘" + book.getTitle() + "’ is up to date with " + found.source);
            return;
        }
        Node thumb = hasCover ? CoverArt.coverFor(book, session.getDataFolder(), 60, 90, false) : null;
        RefreshDialog.open(shell.getWindow(), book.getTitle(), changes, thumb, new RefreshDialog.Done() {
            @Override
            public void apply(List<RefreshDetails.Change> chosen) {
                RefreshFlow.this.apply(chosen, "Updated ‘" + book.getTitle() + "’", redraw);
            }
        });
    }

    /**
     * E146: the bulk refresh, from the bulk bar or Stats' "Fill…".
     *
     * @param books  the books to refresh
     * @param preset the rule each field starts with
     * @param redraw shows the changed books again after Apply or Undo
     */
    void refreshMany(List<Book> books, Map<RefreshDetails.Field, RefreshDetails.Rule> preset,
                     final Runnable redraw) {
        BulkRefreshDialog.open(shell.getWindow(), books, session.newLookup(), session.getDataFolder(), preset,
                new RefreshDialog.Done() {
                    @Override
                    public void apply(List<RefreshDetails.Change> chosen) {
                        RefreshFlow.this.apply(chosen, null, redraw);
                    }
                });
    }

    /**
     * Applies the chosen changes, saves (ADR 0001), redraws, and offers Undo
     * (E147), which restores every field and cover file and saves again.
     *
     * @param message the toast's start, or null for "Updated N books"
     */
    void apply(List<RefreshDetails.Change> chosen, String message, final Runnable redraw) {
        List<String> problems = new ArrayList<>();
        final List<RefreshDetails.Snapshot> before;
        try {
            before = RefreshDetails.apply(chosen, session.getDataFolder(), problems);
        } catch (IOException e) {
            shell.toast("Nothing was changed: an old cover could not be read (" + e.getMessage() + ")");
            return;
        }
        shell.saveChanges();
        redraw.run();
        String text = message != null ? message : "Updated " + WishlistPicker.count(before.size(), "book", "books");
        text += "  ·  " + WishlistPicker.count(chosen.size(), "change", "changes");
        if (!problems.isEmpty()) {
            text += "  ·  " + problems.get(0);
        }
        shell.toast(text, before.size() == 1 ? "Undo" : "Undo all", new Runnable() {
            @Override
            public void run() {
                try {
                    RefreshDetails.undo(before);
                } catch (IOException e) {
                    shell.toast("A cover could not be put back: " + e.getMessage());
                }
                // E153: Undo changes memory, then saves again.
                shell.saveChanges();
                redraw.run();
            }
        });
    }
}
