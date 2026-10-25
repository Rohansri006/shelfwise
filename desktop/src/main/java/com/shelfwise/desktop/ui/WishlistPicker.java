package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionManager;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Add to wishlist…" from the Add page (E127), plus the small wishlist
 * helpers the Wishlist page shares with it.
 * <p>
 * The picker is a {@link ContextMenu} dropped below the button: one item
 * per wishlist, then "New wishlist…". A menu rather than a dialog, because
 * choosing a list is one click and needs no OK button.
 * <p>
 * All members are static: the class holds no state of its own, it only
 * groups code that two controllers would otherwise each copy.
 */
final class WishlistPicker {

    /** Called with the list the user picked (or just created). */
    interface Chosen {
        void chosen(Wishlist wishlist);
    }

    /** A utility class is never instantiated, so its constructor is private. */
    private WishlistPicker() {
    }

    /**
     * Shows the list picker below a button.
     *
     * @param anchor  the button that opened it
     * @param session the open collection, whose wishlists are listed
     * @param shell   the window, to save a list made with "New wishlist…"
     * @param chosen  told which list was picked; not called on Esc
     */
    static void show(final Node anchor, final Session session, final Shell shell, final Chosen chosen) {
        ContextMenu menu = new ContextMenu();
        List<Wishlist> lists = session.getManager().getWishlists();
        for (int i = 0; i < lists.size(); i++) {
            final Wishlist list = lists.get(i);
            MenuItem item = new MenuItem(list.getName() + " (" + list.getEntryCount() + ")");
            item.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    chosen.chosen(list);
                }
            });
            menu.getItems().add(item);
        }
        if (!lists.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem create = new MenuItem("New wishlist…");
        create.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                Wishlist made = askForNewList(anchor, session.getManager());
                if (made != null) {
                    // A new list is a change of its own (ADR 0001), saved even
                    // if what was meant to go on it is then cancelled.
                    shell.saveChanges();
                    chosen.chosen(made);
                }
            }
        });
        menu.getItems().add(create);
        menu.show(anchor, Side.BOTTOM, 0, 4);
    }

    /**
     * Asks for a new list's name until the manager accepts one (E124's
     * rules: not blank, not taken ignoring case) or the user cancels.
     *
     * @return the new list, already added to the collection, or null
     */
    static Wishlist askForNewList(Node owner, CollectionManager manager) {
        String problem = null;
        while (true) {
            TextInputDialog dialog = new TextInputDialog("New wishlist");
            dialog.setHeaderText(problem);
            dialog.setContentText("Name:");
            dialog.setTitle("New wishlist");
            dialog.initOwner(owner.getScene().getWindow());
            Optional<String> name = dialog.showAndWait();
            if (!name.isPresent()) {
                return null;
            }
            Wishlist list;
            try {
                list = new Wishlist(name.get());
                manager.addWishlist(list);
                return list;
            } catch (IllegalArgumentException e) {
                // Shown as the dialog's header on the next round of the loop.
                problem = e.getMessage();
            }
        }
    }

    /**
     * @param raw an ISBN as typed or stored, in any form
     * @return its ISBN-13, or null if it is blank or not a valid ISBN
     */
    static String isbn13OrNull(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return IsbnValidator.toIsbn13(raw);
        } catch (InvalidISBNException e) {
            return null;
        }
    }

    /**
     * Puts an entry back at a position, for Undo. {@code Wishlist} can only
     * append, so the entries after the position are taken off, the entry is
     * added, and they are added back behind it.
     */
    static void insertEntry(Wishlist list, WishlistEntry entry, int index) {
        List<WishlistEntry> after = new ArrayList<>();
        List<WishlistEntry> all = list.getEntries();
        for (int i = index; i < all.size(); i++) {
            after.add(all.get(i));
        }
        for (int i = 0; i < after.size(); i++) {
            list.removeEntry(after.get(i));
        }
        list.addEntry(entry);
        for (int i = 0; i < after.size(); i++) {
            list.addEntry(after.get(i));
        }
    }

    /**
     * Puts a deleted list back at its old position, for Undo. The same trick
     * as {@link #insertEntry}: the manager appends, so the lists after the
     * position are removed and added again.
     */
    static void insertList(CollectionManager manager, Wishlist list, int index) {
        List<Wishlist> after = new ArrayList<>();
        List<Wishlist> all = manager.getWishlists();
        for (int i = index; i < all.size(); i++) {
            after.add(all.get(i));
        }
        for (int i = 0; i < after.size(); i++) {
            manager.removeWishlist(after.get(i));
        }
        manager.addWishlist(list);
        for (int i = 0; i < after.size(); i++) {
            manager.addWishlist(after.get(i));
        }
    }

    /**
     * Asks a yes/no question (E118: every delete asks; E127: the "already
     * own it" warning).
     *
     * @param action the text of the "yes" button
     * @param danger true to draw that button red, for a delete
     * @return true if the user pressed it
     */
    static boolean ask(Node owner, String message, String action, boolean danger) {
        return action.equals(choose(owner, message, danger, action));
    }

    /**
     * Asks a question with Cancel and any number of other answers; the last
     * one is drawn red when {@code danger} is true, the same way as the
     * Trash's confirms: Modena derives a button's whole look from
     * {@code -fx-base}.
     *
     * @param answers the buttons beside Cancel ({@code ...} is varargs: any number)
     * @return the text of the button pressed, or "Cancel"
     */
    static String choose(Node owner, String message, boolean danger, String... answers) {
        Alert alert = new Alert(danger ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION, message,
                ButtonType.CANCEL);
        ButtonType last = null;
        for (int i = 0; i < answers.length; i++) {
            last = new ButtonType(answers[i]);
            alert.getButtonTypes().add(last);
        }
        alert.setHeaderText(null);
        alert.initOwner(owner.getScene().getWindow());
        if (danger && last != null) {
            alert.getDialogPane().lookupButton(last).setStyle("-fx-base: #dc2626;");
        }
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() != ButtonType.CANCEL ? answer.get().getText() : "Cancel";
    }

    /** "1 entry" / "3 entries": the count with the right word. */
    static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
