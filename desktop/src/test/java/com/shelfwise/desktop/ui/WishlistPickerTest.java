package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the wishlist helpers behind Undo and the ISBN checks. Undo must
 * put an entry or a list back exactly where it was, although the core can
 * only append; these tests pin that down without starting JavaFX.
 */
class WishlistPickerTest {

    private static String titles(Wishlist list) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < list.getEntries().size(); i++) {
            s.append(list.getEntries().get(i).getTitle()).append(i < list.getEntries().size() - 1 ? "," : "");
        }
        return s.toString();
    }

    @Test
    @DisplayName("an entry removed from the middle goes back to the same place")
    void insertEntryRestoresPosition() {
        Wishlist list = new Wishlist("To buy");
        WishlistEntry a = new WishlistEntry("A");
        WishlistEntry b = new WishlistEntry("B");
        WishlistEntry c = new WishlistEntry("C");
        list.addEntry(a);
        list.addEntry(b);
        list.addEntry(c);
        list.removeEntry(b);
        WishlistPicker.insertEntry(list, b, 1);
        assertEquals("A,B,C", titles(list));
    }

    @Test
    @DisplayName("an entry put back past the end of a shorter list goes last")
    void insertEntryAtEnd() {
        Wishlist list = new Wishlist("To buy");
        WishlistEntry a = new WishlistEntry("A");
        list.addEntry(a);
        WishlistEntry z = new WishlistEntry("Z");
        WishlistPicker.insertEntry(list, z, 1);
        assertEquals("A,Z", titles(list));
    }

    @Test
    @DisplayName("a deleted list goes back to its old position among the lists")
    void insertListRestoresPosition() {
        CollectionManager manager = new CollectionManager();
        Wishlist first = new Wishlist("First");
        Wishlist middle = new Wishlist("Middle");
        Wishlist last = new Wishlist("Last");
        manager.addWishlist(first);
        manager.addWishlist(middle);
        manager.addWishlist(last);
        manager.removeWishlist(middle);
        WishlistPicker.insertList(manager, middle, 1);
        assertEquals(middle, manager.getWishlists().get(1));
        assertEquals(last, manager.getWishlists().get(2));
    }

    @Test
    @DisplayName("an ISBN-10 with hyphens becomes its ISBN-13")
    void isbn10BecomesIsbn13() {
        // Glimpses of World History: 0-14-303105-8 is 9780143031055.
        assertEquals("9780143031055", WishlistPicker.isbn13OrNull("0-14-303105-8"));
    }

    @Test
    @DisplayName("a blank or invalid ISBN gives null rather than an error")
    void blankOrInvalidIsNull() {
        assertNull(WishlistPicker.isbn13OrNull("  "));
        assertNull(WishlistPicker.isbn13OrNull(null));
        assertNull(WishlistPicker.isbn13OrNull("9780143031056"));
    }

    @Test
    @DisplayName("counts read \"1 entry\" and \"3 entries\"")
    void countWording() {
        assertEquals("1 entry", WishlistPicker.count(1, "entry", "entries"));
        assertEquals("3 entries", WishlistPicker.count(3, "entry", "entries"));
    }
}
