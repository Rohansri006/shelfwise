package com.shelfwise.core.model;

import com.shelfwise.core.exception.InvalidISBNException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Wishlist}. */
class WishlistTest {

    @Test
    @DisplayName("a new wishlist is empty and has its own identity")
    void newWishlistIsEmpty() {
        Wishlist w = new Wishlist("Birthday ideas");
        assertEquals("Birthday ideas", w.getName());
        assertTrue(w.isEmpty());
        assertEquals(0, w.getEntryCount());
        assertNotNull(w.getId());
    }

    @Test
    @DisplayName("the storage constructor keeps the id and starts empty")
    void storageConstructor() {
        // Empty because the DAO supplies the real entries after loading.
        Wishlist w = new Wishlist("wishlist-1", "Birthday ideas");
        assertEquals("wishlist-1", w.getId());
        assertTrue(w.isEmpty());
    }

    @Test
    @DisplayName("blank and null names are rejected, and names are trimmed")
    void nameValidation() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Wishlist("  ");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Wishlist(null);
            }
        });
        assertEquals("Birthday ideas", new Wishlist(" Birthday ideas  ").getName());
    }

    @Test
    @DisplayName("entries are kept in the order they were added")
    void addEntryKeepsOrder() {
        Wishlist w = new Wishlist("Birthday ideas");
        WishlistEntry first = new WishlistEntry("Dune");
        WishlistEntry second = new WishlistEntry("Neuromancer");
        w.addEntry(first);
        w.addEntry(second);

        assertEquals(2, w.getEntryCount());
        assertFalse(w.isEmpty());
        assertSame(first, w.getEntries().get(0));
        assertSame(second, w.getEntries().get(1));
    }

    @Test
    @DisplayName("a null entry is rejected")
    void nullEntryRejected() {
        final Wishlist w = new Wishlist("Birthday ideas");
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                w.addEntry(null);
            }
        });
    }

    @Test
    @DisplayName("the same entry cannot be added twice, but two entries may share a title")
    void duplicateEntryRejectedSameTitleAllowed() {
        final Wishlist w = new Wishlist("Birthday ideas");
        final WishlistEntry entry = new WishlistEntry("Dune");
        w.addEntry(entry);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        w.addEntry(entry);
                    }
                });
        assertTrue(e.getMessage().contains("already on"), e.getMessage());

        // Wanting the hardback and a cheap paperback is legitimate: same
        // title, separate entries with separate ids.
        w.addEntry(new WishlistEntry("Dune"));
        assertEquals(2, w.getEntryCount());
    }

    @Test
    @DisplayName("an ISBN already on the list is refused, however it is written; entries without one are not compared")
    void sameIsbnRefused() {
        final Wishlist w = new Wishlist("Birthday ideas");
        WishlistEntry first = new WishlistEntry("Glimpses of World History");
        first.setIsbn("9780143031055");
        w.addEntry(first);

        // The ISBN-10 form of the same book, under another title.
        final WishlistEntry again = new WishlistEntry("Glimpses (paperback)");
        again.setIsbn("0-14-303105-8");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        w.addEntry(again);
                    }
                });
        assertTrue(e.getMessage().contains("same ISBN"), e.getMessage());
        assertSame(first, w.findEntryByIsbn("0143031058"));

        w.addEntry(new WishlistEntry("No ISBN yet"));
        w.addEntry(new WishlistEntry("No ISBN either"));
        assertEquals(3, w.getEntryCount());
    }

    @Test
    @DisplayName("removeEntry reports whether the entry was there")
    void removeEntry() {
        Wishlist w = new Wishlist("Birthday ideas");
        WishlistEntry entry = new WishlistEntry("Dune");
        w.addEntry(entry);

        assertTrue(w.removeEntry(entry));
        assertTrue(w.isEmpty());
        assertFalse(w.removeEntry(entry));
    }

    @Test
    @DisplayName("findEntryById finds an entry, or returns null")
    void findEntryById() {
        Wishlist w = new Wishlist("Birthday ideas");
        WishlistEntry entry = new WishlistEntry("Dune");
        w.addEntry(entry);

        assertSame(entry, w.findEntryById(entry.getId()));
        assertNull(w.findEntryById("no-such-id"));
        assertNull(w.findEntryById(null));
    }

    @Test
    @DisplayName("entries getter is unmodifiable")
    void entriesUnmodifiable() {
        final Wishlist w = new Wishlist("Birthday ideas");
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                w.getEntries().add(new WishlistEntry("Sneaky"));
            }
        });
    }

    @Test
    @DisplayName("marking an entry as bought returns a book and removes the entry")
    void markAsBought() throws InvalidISBNException {
        Wishlist w = new Wishlist("Birthday ideas");
        WishlistEntry entry = new WishlistEntry("Dune");
        entry.setIsbn("978-0-14-303105-5");
        entry.setAuthor("Frank Herbert");
        w.addEntry(entry);

        Book book = w.markAsBought(entry);

        assertEquals("Dune", book.getTitle());
        assertEquals("9780143031055", book.getIsbn());
        assertEquals("Frank Herbert", book.getPrimaryAuthor().getName());
        assertTrue(w.isEmpty());
    }

    @Test
    @DisplayName("a bad ISBN stops the purchase and leaves the entry on the list")
    void markAsBoughtWithBadIsbnKeepsEntry() {
        // The wishlist accepts ISBNs unchecked, so validation first happens
        // here. If the entry were removed before the book was built, a typo
        // would silently lose it.
        final Wishlist w = new Wishlist("Birthday ideas");
        final WishlistEntry entry = new WishlistEntry("Dune");
        entry.setIsbn("9780143031056"); // wrong check digit
        w.addEntry(entry);

        assertThrows(InvalidISBNException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                w.markAsBought(entry);
            }
        });
        assertEquals(1, w.getEntryCount());
        assertSame(entry, w.getEntries().get(0));
    }

    @Test
    @DisplayName("an entry from a different list cannot be marked as bought here")
    void markAsBoughtRejectsForeignEntry() {
        final Wishlist birthday = new Wishlist("Birthday ideas");
        Wishlist other = new Wishlist("Out of print");
        final WishlistEntry entry = new WishlistEntry("Dune");
        other.addEntry(entry);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        birthday.markAsBought(entry);
                    }
                });
        assertTrue(e.getMessage().contains("Dune"), e.getMessage());
        assertEquals(1, other.getEntryCount());
    }

    @Test
    @DisplayName("equality uses the id alone, so it survives a rename")
    void equalityById() {
        Wishlist original = new Wishlist("wishlist-1", "Birthday ideas");
        Wishlist renamed = new Wishlist("wishlist-1", "Birthday 2027");
        assertEquals(original, renamed);
        assertEquals(original.hashCode(), renamed.hashCode());
        assertEquals("Birthday 2027", renamed.toString());

        assertNotEquals(new Wishlist("Birthday ideas"), new Wishlist("Birthday ideas"));
    }
}
