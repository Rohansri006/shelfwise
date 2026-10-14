package com.shelfwise.core.service;

import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link CollectionManager}. */
class CollectionManagerTest {

    /** A checksum-valid ISBN-13, also one of the demo dataset seeds. */
    private static final String DUNE_ISBN = "9780143031055";

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a new manager holds nothing")
    void startsEmpty() {
        CollectionManager m = new CollectionManager();
        assertTrue(m.getItems().isEmpty());
        assertTrue(m.getTrash().isEmpty());
    }

    @Test
    @DisplayName("each addItem overload adds a findable book")
    void addItemOverloads() throws Exception {
        CollectionManager m = new CollectionManager();
        Book byTitle = m.addItem("Neuromancer");
        Book byIsbn = m.addItem(DUNE_ISBN, "Dune");
        Book built = new Book("Foundation");
        m.addItem(built);

        assertEquals(3, m.getItems().size());
        assertSame(byTitle, m.findItemById(byTitle.getId()));
        assertSame(byIsbn, m.findBookByIsbn(DUNE_ISBN));
        assertSame(built, m.findItemById(built.getId()));
    }

    @Test
    @DisplayName("an ISBN already in the collection is refused, in either written form")
    void duplicateIsbnRefused() throws Exception {
        final CollectionManager m = new CollectionManager();
        final Book first = m.addItem(DUNE_ISBN, "Dune");

        DuplicateItemException e = assertThrows(DuplicateItemException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        // Hyphenated: the same ISBN as the plain one above.
                        m.addItem("978-0-14-303105-5", "Dune (again)");
                    }
                });
        // The id lets the screen offer "add a copy to the existing book".
        assertEquals(first.getId(), e.getExistingItemId());
        assertEquals(1, m.getItems().size());
    }

    @Test
    @DisplayName("a duplicate of a trashed book is still refused")
    void duplicateOfTrashedRefused() throws Exception {
        // If this were allowed, restoring the trashed book would leave two
        // books with one ISBN.
        final CollectionManager m = new CollectionManager();
        Book first = m.addItem(DUNE_ISBN, "Dune");
        m.moveToTrash(first.getId());

        assertThrows(DuplicateItemException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                m.addItem(DUNE_ISBN, "Dune");
            }
        });
    }

    @Test
    @DisplayName("books without an ISBN are never duplicates of each other")
    void noIsbnNoDuplicateCheck() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        m.addItem("Untitled notebook");
        m.addItem("Untitled notebook");
        assertEquals(2, m.getItems().size());
    }

    @Test
    @DisplayName("an invalid ISBN is refused before anything is added")
    void invalidIsbnRefused() {
        final CollectionManager m = new CollectionManager();
        assertThrows(InvalidISBNException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                m.addItem("9780143031056", "Dune");
            }
        });
        assertTrue(m.getItems().isEmpty());
    }

    @Test
    @DisplayName("the same item object cannot be added twice")
    void sameItemTwiceRefused() throws DuplicateItemException {
        final CollectionManager m = new CollectionManager();
        final Book book = new Book("Dune");
        m.addItem(book);
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                m.addItem(book);
            }
        });
    }

    @Test
    @DisplayName("lookups for unknown or null keys return null")
    void unknownLookups() {
        CollectionManager m = new CollectionManager();
        assertNull(m.findItemById("nope"));
        assertNull(m.findItemById(null));
        assertNull(m.findBookByIsbn(DUNE_ISBN));
        assertNull(m.findBookByIsbn(null));
    }

    // ------------------------------------------------------------------
    // Trash
    // ------------------------------------------------------------------

    @Test
    @DisplayName("trashing moves an item out of the live list, and restoring brings it back")
    void trashRoundTrip() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        Book book = m.addItem("Dune");

        m.moveToTrash(book.getId());
        assertTrue(m.getItems().isEmpty());
        assertEquals(1, m.getTrash().size());

        m.restoreFromTrash(book.getId());
        assertEquals(1, m.getItems().size());
        assertTrue(m.getTrash().isEmpty());
    }

    @Test
    @DisplayName("permanent deletion is refused for an item not in the trash")
    void deletePermanentlyNeedsTrash() throws DuplicateItemException {
        // Two deliberate steps before data is destroyed.
        final CollectionManager m = new CollectionManager();
        final Book book = m.addItem("Dune");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        m.deletePermanently(book.getId());
                    }
                });
        assertTrue(e.getMessage().contains("trash"), e.getMessage());

        m.moveToTrash(book.getId());
        m.deletePermanently(book.getId());
        assertNull(m.findItemById(book.getId()));
    }

    @Test
    @DisplayName("emptying the trash deletes only trashed items and reports how many")
    void emptyTrash() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        Book keep = m.addItem("Keep me");
        m.moveToTrash(m.addItem("Bin one").getId());
        m.moveToTrash(m.addItem("Bin two").getId());

        assertEquals(2, m.emptyTrash());
        assertEquals(1, m.getItems().size());
        assertSame(keep, m.getItems().get(0));
    }

    @Test
    @DisplayName("trash operations on an unknown id are refused")
    void trashUnknownId() {
        final CollectionManager m = new CollectionManager();
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.moveToTrash("nope");
            }
        });
    }

    // ------------------------------------------------------------------
    // Load and save
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a snapshot rebuilt into a new manager holds the same collection")
    void snapshotRoundTrip() throws DuplicateItemException {
        CollectionManager original = new CollectionManager();
        Book live = original.addItem("Dune");
        Book trashed = original.addItem("Neuromancer");
        original.moveToTrash(trashed.getId());

        CollectionData data = original.toCollectionData();
        CollectionManager reloaded = new CollectionManager(data);

        // The trash is saved too, so it survives a restart.
        assertEquals(2, data.getBooks().size());
        assertNotNull(data.getSavedAt());
        assertSame(live, reloaded.findItemById(live.getId()));
        assertEquals(1, reloaded.getTrash().size());
    }

    @Test
    @DisplayName("loading indexes every category node, not just the roots")
    void loadIndexesAllCategoryNodes() {
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        fiction.addChild(scifi);
        List<Category> categories = new ArrayList<>();
        categories.add(fiction);
        categories.add(scifi);

        CollectionManager m = new CollectionManager(
                new CollectionData(null, categories, null, null, null, null));

        assertEquals(2, m.toCollectionData().getCategories().size());
    }

    @Test
    @DisplayName("loading from null data is refused")
    void loadNullRefused() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new CollectionManager(null);
            }
        });
    }

    @Test
    @DisplayName("items are returned through their abstract parent type")
    void itemsAreCollectionItems() throws DuplicateItemException {
        // The manager works in CollectionItem, not Book, so a future item
        // type needs no change here.
        CollectionManager m = new CollectionManager();
        m.addItem("Dune");
        CollectionItem item = m.getItems().get(0);
        assertEquals("Dune", item.getTitle());
    }
}
