package com.shelfwise.core.service;

import com.shelfwise.core.exception.CategoryNotEmptyException;
import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.exception.LocationNotEmptyException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
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
    @DisplayName("an ISBN-10 is refused when its ISBN-13 twin is already in the collection")
    void isbn10DuplicateOfIsbn13Refused() throws Exception {
        // The same edition printed with both barcodes: scanning the old
        // ISBN-10 must not add a second book.
        final CollectionManager m = new CollectionManager();
        Book first = m.addItem(DUNE_ISBN, "Dune");

        DuplicateItemException e = assertThrows(DuplicateItemException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        m.addItem("0143031058", "Dune (old barcode)");
                    }
                });
        assertEquals(first.getId(), e.getExistingItemId());
        assertSame(first, m.findBookByIsbn("0-14-303105-8"));
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
    @DisplayName("once a book is deleted for good, its ISBN can be added again")
    void isbnFreedByDeletion() throws Exception {
        // Guards the ISBN index: if deletion left the ISBN in it, the
        // duplicate check would refuse a book that no longer exists.
        CollectionManager m = new CollectionManager();
        Book first = m.addItem(DUNE_ISBN, "Dune");
        m.moveToTrash(first.getId());
        m.deletePermanently(first.getId());
        assertNull(m.findBookByIsbn(DUNE_ISBN));

        Book second = m.addItem(DUNE_ISBN, "Dune");
        m.moveToTrash(second.getId());
        m.emptyTrash();
        assertNull(m.findBookByIsbn(DUNE_ISBN));

        assertSame(m.addItem(DUNE_ISBN, "Dune"), m.findBookByIsbn(DUNE_ISBN));
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
    void snapshotRoundTrip() throws Exception {
        CollectionManager original = new CollectionManager();
        Book live = original.addItem(DUNE_ISBN, "Dune");
        Book trashed = original.addItem("Neuromancer");
        original.moveToTrash(trashed.getId());

        CollectionData data = original.toCollectionData();
        CollectionManager reloaded = new CollectionManager(data);

        // The trash is saved too, so it survives a restart.
        assertEquals(2, data.getBooks().size());
        assertNotNull(data.getSavedAt());
        assertSame(live, reloaded.findItemById(live.getId()));
        // Loading rebuilds the ISBN index too, not only the id map.
        assertSame(live, reloaded.findBookByIsbn(DUNE_ISBN));
        assertEquals(1, reloaded.getTrash().size());
    }

    @Test
    @DisplayName("the currency survives a load and save, and can be changed")
    void currencyKept() {
        // Before this, toCollectionData always said INR, so a collection
        // priced in euros was silently relabelled on its first save.
        final CollectionManager m = new CollectionManager(
                new CollectionData(null, null, null, null, null, null, "eur"));
        assertEquals("EUR", m.getCurrencyCode());
        assertEquals("EUR", m.toCollectionData().getCurrencyCode());

        m.setCurrencyCode("usd");
        assertEquals("USD", m.toCollectionData().getCurrencyCode());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.setCurrencyCode("XYZ");
            }
        });
        assertEquals("USD", m.getCurrencyCode());
        assertEquals("INR", new CollectionManager().getCurrencyCode());
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

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Test
    @DisplayName("text search covers title, subtitle, author, publisher and ISBN, ignoring case")
    void textSearchFields() throws Exception {
        CollectionManager m = new CollectionManager();
        Book dune = m.addItem(DUNE_ISBN, "Dune");
        dune.setSubtitle("Deluxe Edition");
        dune.setPublisher("Penguin");
        dune.addContributor(new Contributor("Frank Herbert"));
        m.addItem("Neuromancer");

        assertEquals(1, m.search("dUNe").size());
        assertEquals(1, m.search("deluxe").size());
        assertEquals(1, m.search("herbert").size());
        assertEquals(1, m.search("PENGUIN").size());
        assertEquals(0, m.search("Gibson").size());
    }

    @Test
    @DisplayName("an ISBN fragment typed with hyphens still finds the book")
    void textSearchIsbnWithHyphens() throws Exception {
        CollectionManager m = new CollectionManager();
        Book dune = m.addItem(DUNE_ISBN, "Dune");
        assertSame(dune, m.search("978-0-14").get(0));
    }

    @Test
    @DisplayName("a complete ISBN-10 finds the book stored under its ISBN-13")
    void textSearchIsbn10() throws Exception {
        CollectionManager m = new CollectionManager();
        Book dune = m.addItem(DUNE_ISBN, "Dune");
        assertEquals(1, m.search("0-14-303105-8").size());
        assertSame(dune, m.search("0143031058").get(0));
    }

    @Test
    @DisplayName("items, trash and search results come back sorted by title, ignoring case")
    void listsSortedByTitle() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        m.addItem("neuromancer");
        m.addItem("Dune");
        m.addItem("Anathem");
        m.moveToTrash(m.addItem("Zorba the Greek").getId());
        m.moveToTrash(m.addItem("brave new world").getId());

        List<CollectionItem> live = m.getItems();
        assertEquals("Anathem", live.get(0).getTitle());
        assertEquals("Dune", live.get(1).getTitle());
        // Lower case does not push it after the capitalised titles.
        assertEquals("neuromancer", live.get(2).getTitle());

        assertEquals("brave new world", m.getTrash().get(0).getTitle());
        assertEquals("Zorba the Greek", m.getTrash().get(1).getTitle());

        List<CollectionItem> found = m.search("n");
        assertEquals("Anathem", found.get(0).getTitle());
        assertEquals("Dune", found.get(1).getTitle());
    }

    @Test
    @DisplayName("text search covers custom field values but not their names")
    void textSearchCustomFieldValues() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        Book dune = m.addItem("Dune");
        dune.setCustomField("Acquired at", "Daryaganj market");
        Book other = m.addItem("Neuromancer");
        other.setCustomField("Signed", "no");

        assertSame(dune, m.search("daryaganj").get(0));
        assertEquals(1, m.search("daryaganj").size());
        // The field name alone finds nothing.
        assertTrue(m.search("acquired").isEmpty());
    }

    @Test
    @DisplayName("blank text returns every live item")
    void blankSearchReturnsAll() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        m.addItem("Dune");
        m.addItem("Neuromancer");
        assertEquals(2, m.search("  ").size());
        assertEquals(2, m.search((String) null).size());
    }

    @Test
    @DisplayName("trashed items never appear in search results")
    void searchExcludesTrash() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        Book dune = m.addItem("Dune");
        dune.setReadingStatus(ReadingStatus.FINISHED);
        m.moveToTrash(dune.getId());

        assertEquals(0, m.search("Dune").size());
        assertEquals(0, m.search(ReadingStatus.FINISHED).size());
    }

    @Test
    @DisplayName("category search includes books filed under sub-categories")
    void categorySearchIncludesDescendants() throws DuplicateItemException {
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        fiction.addChild(scifi);

        CollectionManager m = new CollectionManager();
        Book dune = m.addItem("Dune");
        dune.addCategoryId(scifi.getId());
        m.addItem("Uncategorised");

        assertEquals(1, m.search(fiction).size());
        assertSame(dune, m.search(scifi).get(0));
    }

    @Test
    @DisplayName("location search finds a book if any one of its copies is inside")
    void locationSearchAnyCopy() throws DuplicateItemException {
        Location shelf = Location.createShelfWithRows("Study shelf", 2);
        Location row2 = shelf.getChildren().get(1);
        Location bedroom = new Location("Bedroom");

        CollectionManager m = new CollectionManager();
        Book dune = m.addItem("Dune");
        dune.getCopies().get(0).setLocationId(bedroom.getId());
        Copy second = new Copy();
        second.setLocationId(row2.getId());
        dune.addCopy(second);

        // Found via the shelf (copy in row 2) and via the bedroom (first copy).
        assertSame(dune, m.search(shelf).get(0));
        assertSame(dune, m.search(bedroom).get(0));
        assertEquals(0, m.search(new Location("Loft")).size());
    }

    @Test
    @DisplayName("status search returns books at that status only")
    void statusSearch() throws DuplicateItemException {
        CollectionManager m = new CollectionManager();
        Book reading = m.addItem("Dune");
        reading.setReadingStatus(ReadingStatus.READING);
        m.addItem("Neuromancer");

        assertSame(reading, m.search(ReadingStatus.READING).get(0));
        assertEquals(1, m.search(ReadingStatus.UNREAD).size());
    }

    @Test
    @DisplayName("the manager can be used through the Searchable interface")
    void usableAsSearchable() throws DuplicateItemException {
        // Declared type is the interface: the screen needs only "something
        // searchable", not the whole manager.
        CollectionManager m = new CollectionManager();
        m.addItem("Dune");
        Searchable searchable = m;
        assertEquals(1, searchable.search("dune").size());
    }

    // ------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------

    @Test
    @DisplayName("adding a category registers its whole subtree")
    void addCategoryIndexesSubtree() {
        CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        m.addCategory(fiction);
        Category scifi = new Category("Science fiction");
        m.addCategory(scifi, fiction);

        assertSame(scifi, m.findCategoryById(scifi.getId()));
        assertEquals(fiction.getId(), scifi.getParentId());
        assertEquals(1, m.getRootCategories().size());
    }

    @Test
    @DisplayName("two top-level categories cannot share a name")
    void duplicateRootCategoryRefused() {
        // The tree class cannot see this: a root has no parent to check its
        // siblings. The database had the same gap with NULL parent ids.
        final CollectionManager m = new CollectionManager();
        m.addCategory(new Category("Fiction"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        m.addCategory(new Category("fiction"));
                    }
                });
        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
    }

    @Test
    @DisplayName("the same category name is allowed under different parents")
    void sameNameUnderDifferentParents() {
        CollectionManager m = new CollectionManager();
        Category nonFiction = new Category("Non-fiction");
        Category reference = new Category("Reference");
        m.addCategory(nonFiction);
        m.addCategory(reference);

        m.addCategory(new Category("Finance"), nonFiction);
        m.addCategory(new Category("Finance"), reference);

        assertEquals(4, m.toCollectionData().getCategories().size());
    }

    @Test
    @DisplayName("moving a category to the top level checks top-level names")
    void moveCategoryToRoot() {
        final CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        m.addCategory(fiction);
        final Category nested = new Category("Fiction");
        Category other = new Category("Other");
        m.addCategory(other);
        m.addCategory(nested, other);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.moveCategory(nested, null);
            }
        });

        Category poetry = new Category("Poetry");
        m.addCategory(poetry, other);
        m.moveCategory(poetry, null);
        assertTrue(poetry.isRoot());
        // Only the nested "Fiction" is left under "Other".
        assertEquals(1, other.getChildren().size());
    }

    @Test
    @DisplayName("deleting a category with a book anywhere beneath it is refused, naming the count")
    void deleteNonEmptyCategoryRefused() throws DuplicateItemException {
        final CollectionManager m = new CollectionManager();
        final Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        m.addCategory(fiction);
        m.addCategory(scifi, fiction);
        Book dune = m.addItem("Dune");
        dune.addCategoryId(scifi.getId());
        // A trashed book still counts: it is still stored.
        Book trashed = m.addItem("Neuromancer");
        trashed.addCategoryId(scifi.getId());
        m.moveToTrash(trashed.getId());

        CategoryNotEmptyException e = assertThrows(CategoryNotEmptyException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        m.deleteCategory(fiction);
                    }
                });
        assertEquals(2, e.getBookCount());
        assertSame(fiction, m.findCategoryById(fiction.getId()));
    }

    @Test
    @DisplayName("deleting an empty category removes its whole subtree")
    void deleteEmptyCategory() throws CategoryNotEmptyException {
        CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        m.addCategory(fiction);
        m.addCategory(scifi, fiction);

        m.deleteCategory(fiction);

        assertNull(m.findCategoryById(fiction.getId()));
        assertNull(m.findCategoryById(scifi.getId()));
        assertTrue(m.getRootCategories().isEmpty());
    }

    @Test
    @DisplayName("unfile moves every book in the subtree, trashed ones too, to the target, after which the delete succeeds")
    void unfileMovesBooksToTarget() throws Exception {
        CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        Category cyber = new Category("Cyberpunk");
        m.addCategory(fiction);
        m.addCategory(scifi, fiction);
        m.addCategory(cyber, scifi);
        Book dune = m.addItem("Dune");
        dune.addCategoryId(scifi.getId());
        // Filed twice inside the subtree, and once already under the target.
        Book neuromancer = m.addItem("Neuromancer");
        neuromancer.addCategoryId(scifi.getId());
        neuromancer.addCategoryId(cyber.getId());
        neuromancer.addCategoryId(fiction.getId());
        m.moveToTrash(neuromancer.getId());

        assertEquals(2, m.unfile(scifi, fiction));

        assertEquals(List.of(fiction.getId()), dune.getCategoryIds());
        // Not filed under "Fiction" twice.
        assertEquals(List.of(fiction.getId()), neuromancer.getCategoryIds());
        m.deleteCategory(scifi);
        assertNull(m.findCategoryById(cyber.getId()));
    }

    @Test
    @DisplayName("unfile with no target removes the filings and leaves other categories alone")
    void unfileRemovesFilings() throws Exception {
        CollectionManager m = new CollectionManager();
        Category scifi = new Category("Science fiction");
        Category classics = new Category("Classics");
        m.addCategory(scifi);
        m.addCategory(classics);
        Book dune = m.addItem("Dune");
        dune.addCategoryId(scifi.getId());
        dune.addCategoryId(classics.getId());
        Book other = m.addItem("Emma");
        other.addCategoryId(classics.getId());

        assertEquals(1, m.unfile(scifi, null));

        assertEquals(List.of(classics.getId()), dune.getCategoryIds());
        assertEquals(List.of(classics.getId()), other.getCategoryIds());
    }

    @Test
    @DisplayName("unfile refuses a target inside the subtree being emptied")
    void unfileTargetInsideRefused() {
        final CollectionManager m = new CollectionManager();
        final Category fiction = new Category("Fiction");
        final Category scifi = new Category("Science fiction");
        m.addCategory(fiction);
        m.addCategory(scifi, fiction);
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.unfile(fiction, scifi);
            }
        });
    }

    @Test
    @DisplayName("a category not added to the manager is refused")
    void unknownCategoryRefused() {
        final CollectionManager m = new CollectionManager();
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.addCategory(new Category("Orphan"), new Category("Unknown parent"));
            }
        });
    }

    // ------------------------------------------------------------------
    // Locations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a shelf added with its rows registers every row")
    void addLocationIndexesRows() {
        CollectionManager m = new CollectionManager();
        Location shelf = Location.createShelfWithRows("Study shelf", 3);
        m.addLocation(shelf);

        Location row = shelf.getChildren().get(2);
        assertSame(row, m.findLocationById(row.getId()));
        assertEquals(4, m.toCollectionData().getLocations().size());
    }

    @Test
    @DisplayName("two top-level locations cannot share a name")
    void duplicateRootLocationRefused() {
        final CollectionManager m = new CollectionManager();
        m.addLocation(new Location("Study shelf"));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.addLocation(new Location("Study Shelf"));
            }
        });
    }

    @Test
    @DisplayName("deleting a location holding copies is refused, counting copies not books")
    void deleteNonEmptyLocationRefused() throws DuplicateItemException {
        final CollectionManager m = new CollectionManager();
        final Location shelf = Location.createShelfWithRows("Study shelf", 2);
        m.addLocation(shelf);
        Location row1 = shelf.getChildren().get(0);
        Location row2 = shelf.getChildren().get(1);

        // One book, two copies, on two different rows of the same shelf.
        Book dune = m.addItem("Dune");
        dune.getCopies().get(0).setLocationId(row1.getId());
        Copy second = new Copy();
        second.setLocationId(row2.getId());
        dune.addCopy(second);

        LocationNotEmptyException e = assertThrows(LocationNotEmptyException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        m.deleteLocation(shelf);
                    }
                });
        assertEquals(2, e.getCopyCount());
    }

    @Test
    @DisplayName("deleting an empty location removes it and everything inside")
    void deleteEmptyLocation() throws LocationNotEmptyException {
        CollectionManager m = new CollectionManager();
        Location shelf = Location.createShelfWithRows("Study shelf", 2);
        m.addLocation(shelf);
        Location row = shelf.getChildren().get(0);

        m.deleteLocation(shelf);

        assertNull(m.findLocationById(shelf.getId()));
        assertNull(m.findLocationById(row.getId()));
    }

    @Test
    @DisplayName("unshelve moves every copy inside the location, trashed books' too, to the target")
    void unshelveMovesCopies() throws Exception {
        CollectionManager m = new CollectionManager();
        Location shelf = Location.createShelfWithRows("Study shelf", 2);
        Location box = new Location("Box");
        m.addLocation(shelf);
        m.addLocation(box);
        Location row1 = shelf.getChildren().get(0);
        Book dune = m.addItem("Dune");
        dune.getCopies().get(0).setLocationId(row1.getId());
        Book trashed = m.addItem("Emma");
        trashed.getCopies().get(0).setLocationId(shelf.getId());
        m.moveToTrash(trashed.getId());

        assertEquals(2, m.unshelve(shelf, box));

        assertEquals(box.getId(), dune.getCopies().get(0).getLocationId());
        assertEquals(box.getId(), trashed.getCopies().get(0).getLocationId());
        m.deleteLocation(shelf);
        assertNull(m.findLocationById(row1.getId()));
    }

    @Test
    @DisplayName("unshelve with no target leaves the copies unshelved")
    void unshelveWithoutTarget() throws Exception {
        CollectionManager m = new CollectionManager();
        Location shelf = new Location("Study shelf");
        m.addLocation(shelf);
        Book dune = m.addItem("Dune");
        dune.getCopies().get(0).setLocationId(shelf.getId());

        assertEquals(1, m.unshelve(shelf, null));

        assertNull(dune.getCopies().get(0).getLocationId());
    }

    @Test
    @DisplayName("moving a shelf inside one of its own rows is refused")
    void moveLocationCycleRefused() {
        final CollectionManager m = new CollectionManager();
        final Location shelf = Location.createShelfWithRows("Study shelf", 1);
        m.addLocation(shelf);
        final Location row = shelf.getChildren().get(0);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.moveLocation(shelf, row);
            }
        });
    }

    // ------------------------------------------------------------------
    // Series and wishlists
    // ------------------------------------------------------------------

    @Test
    @DisplayName("series names are unique across the collection, ignoring case")
    void duplicateSeriesNameRefused() {
        final CollectionManager m = new CollectionManager();
        Series discworld = new Series("Discworld", 41);
        m.addSeries(discworld);
        assertSame(discworld, m.findSeriesById(discworld.getId()));

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.addSeries(new Series("DISCWORLD"));
            }
        });
    }

    @Test
    @DisplayName("deleting a series keeps its books and takes them out of the series")
    void deleteSeriesReleasesBooks() throws DuplicateItemException {
        // The Java mirror of ON DELETE SET NULL: losing the series must not
        // lose the books.
        CollectionManager m = new CollectionManager();
        Series discworld = new Series("Discworld", 41);
        m.addSeries(discworld);
        Book guards = m.addItem("Guards! Guards!");
        guards.setSeriesId(discworld.getId());
        guards.setSeriesPosition(8);
        Book other = m.addItem("Dune");

        assertEquals(1, m.deleteSeries(discworld));

        assertNull(guards.getSeriesId());
        assertEquals(0, guards.getSeriesPosition());
        assertEquals(2, m.getItems().size());
        assertNull(other.getSeriesId());
        assertTrue(m.getSeries().isEmpty());
    }

    @Test
    @DisplayName("wishlist names are unique, and removing one removes its entries with it")
    void wishlists() {
        final CollectionManager m = new CollectionManager();
        Wishlist birthday = new Wishlist("Birthday ideas");
        m.addWishlist(birthday);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.addWishlist(new Wishlist("birthday IDEAS"));
            }
        });

        assertTrue(m.removeWishlist(birthday));
        assertTrue(m.getWishlists().isEmpty());
        assertTrue(m.toCollectionData().getWishlists().isEmpty());
    }

    @Test
    @DisplayName("marking an entry as bought adds the book and removes the entry")
    void markAsBoughtAddsBook() throws Exception {
        CollectionManager m = new CollectionManager();
        Wishlist wishlist = new Wishlist("Birthday");
        m.addWishlist(wishlist);
        WishlistEntry entry = new WishlistEntry("Glimpses of World History");
        entry.setIsbn("0-14-303105-8");
        wishlist.addEntry(entry);

        Book book = m.markAsBought(wishlist, entry);

        assertSame(book, m.findBookByIsbn(DUNE_ISBN));
        assertTrue(wishlist.isEmpty());
    }

    @Test
    @DisplayName("a bought entry that duplicates a book already owned stays on the wishlist")
    void markAsBoughtDuplicateKeepsEntry() throws Exception {
        // The D35 bug: the entry used to be removed before the duplicate
        // was discovered, so it vanished from the list without becoming a
        // book.
        final CollectionManager m = new CollectionManager();
        Book owned = m.addItem(DUNE_ISBN, "Glimpses of World History");
        final Wishlist wishlist = new Wishlist("Birthday");
        m.addWishlist(wishlist);
        final WishlistEntry entry = new WishlistEntry("Glimpses of World History");
        // The ISBN-10 form: still recognised as the same book.
        entry.setIsbn("0143031058");
        wishlist.addEntry(entry);

        DuplicateItemException e = assertThrows(DuplicateItemException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        m.markAsBought(wishlist, entry);
                    }
                });
        assertEquals(owned.getId(), e.getExistingItemId());
        assertEquals(1, wishlist.getEntryCount());
        assertEquals(1, m.getItems().size());
    }

    @Test
    @DisplayName("a bought entry with an invalid ISBN stays on the wishlist")
    void markAsBoughtInvalidIsbnKeepsEntry() {
        final CollectionManager m = new CollectionManager();
        final Wishlist wishlist = new Wishlist("Birthday");
        m.addWishlist(wishlist);
        final WishlistEntry entry = new WishlistEntry("Dune");
        entry.setIsbn("9780143031056");
        wishlist.addEntry(entry);

        assertThrows(InvalidISBNException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                m.markAsBought(wishlist, entry);
            }
        });
        assertEquals(1, wishlist.getEntryCount());
        assertTrue(m.getItems().isEmpty());
    }

    @Test
    @DisplayName("marking as bought on a wishlist outside the collection is refused")
    void markAsBoughtUnknownWishlist() {
        final CollectionManager m = new CollectionManager();
        final Wishlist stray = new Wishlist("Stray");
        final WishlistEntry entry = new WishlistEntry("Dune");
        stray.addEntry(entry);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                m.markAsBought(stray, entry);
            }
        });
        assertEquals(1, stray.getEntryCount());
    }

    // ------------------------------------------------------------------
    // Edits that span the collection (ADR 0006)
    // ------------------------------------------------------------------

    /** A second checksum-valid ISBN, for tests that need two books. */
    private static final String SECOND_ISBN = "9780141187761";

    @Test
    @DisplayName("changeIsbn moves a book to its new ISBN and frees the old one")
    void changeIsbnReindexes() throws Exception {
        CollectionManager m = new CollectionManager();
        Book book = m.addItem(DUNE_ISBN, "Dune");

        // Given as an ISBN-10, stored as ISBN-13 like every other route in.
        m.changeIsbn(book, "014118776X");

        assertEquals(SECOND_ISBN, book.getIsbn());
        assertSame(book, m.findBookByIsbn(SECOND_ISBN));
        assertNull(m.findBookByIsbn(DUNE_ISBN));
        // The old ISBN is free for another book now.
        assertNotNull(m.addItem(DUNE_ISBN, "Glimpses of World History"));
    }

    @Test
    @DisplayName("changeIsbn refuses another book's ISBN and leaves the book unchanged")
    void changeIsbnRefusesDuplicate() throws Exception {
        final CollectionManager m = new CollectionManager();
        Book first = m.addItem(DUNE_ISBN, "Dune");
        final Book second = m.addItem(SECOND_ISBN, "Nineteen Eighty-Four");

        DuplicateItemException e = assertThrows(DuplicateItemException.class,
                new Executable() {
                    @Override
                    public void execute() throws Throwable {
                        m.changeIsbn(second, DUNE_ISBN);
                    }
                });
        assertEquals(first.getId(), e.getExistingItemId());
        assertEquals(SECOND_ISBN, second.getIsbn());
        assertSame(second, m.findBookByIsbn(SECOND_ISBN));
    }

    @Test
    @DisplayName("changeIsbn accepts the book's own ISBN, and a blank clears it")
    void changeIsbnOwnAndBlank() throws Exception {
        CollectionManager m = new CollectionManager();
        Book book = m.addItem(DUNE_ISBN, "Dune");

        // Retyping the same ISBN with hyphens is not a duplicate of itself.
        m.changeIsbn(book, "978-0-14-303105-5");
        assertSame(book, m.findBookByIsbn(DUNE_ISBN));

        m.changeIsbn(book, "  ");
        assertNull(book.getIsbn());
        assertNull(m.findBookByIsbn(DUNE_ISBN));
    }

    @Test
    @DisplayName("renaming a category to a sibling's name is refused, ignoring case")
    void renameCategoryClash() {
        final CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        final Category reference = new Category("Reference");
        final Category scifi = new Category("Science fiction");
        final Category fantasy = new Category("Fantasy");
        m.addCategory(fiction);
        m.addCategory(reference);
        m.addCategory(scifi, fiction);
        m.addCategory(fantasy, fiction);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.renameCategory(fantasy, "science FICTION");
            }
        });
        assertEquals("Fantasy", fantasy.getName());

        // Top-level categories are siblings of each other.
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.renameCategory(reference, "fiction");
            }
        });

        // A name used elsewhere in the tree is fine, and so is a change of
        // case to the category's own name.
        Category finance = new Category("Finance");
        m.addCategory(finance, reference);
        m.renameCategory(finance, "Fantasy");
        m.renameCategory(scifi, "Science Fiction");
        assertEquals("Science Fiction", scifi.getName());
    }

    @Test
    @DisplayName("renaming a location to a sibling's name is refused, but every shelf may have a Row 1")
    void renameLocationClash() {
        final CollectionManager m = new CollectionManager();
        Location bedroom = new Location("Bedroom");
        Location shelfA = new Location("Shelf A");
        final Location shelfB = new Location("Shelf B");
        m.addLocation(bedroom);
        m.addLocation(shelfA, bedroom);
        m.addLocation(shelfB, bedroom);
        Location rowA = new Location("Row 1");
        Location rowB = new Location("Top");
        m.addLocation(rowA, shelfA);
        m.addLocation(rowB, shelfB);

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.renameLocation(shelfB, "shelf a");
            }
        });
        m.renameLocation(rowB, "Row 1");
        assertEquals("Row 1", rowB.getName());
    }

    @Test
    @DisplayName("renaming a series or wishlist to a name already taken is refused")
    void renameSeriesAndWishlist() {
        final CollectionManager m = new CollectionManager();
        final Series dune = new Series("Dune Chronicles");
        m.addSeries(dune);
        m.addSeries(new Series("Discworld"));
        final Wishlist birthday = new Wishlist("Birthday");
        m.addWishlist(birthday);
        m.addWishlist(new Wishlist("Rare finds"));

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.renameSeries(dune, "DISCWORLD");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.renameWishlist(birthday, "rare finds");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.renameWishlist(birthday, " ");
            }
        });

        m.renameSeries(dune, "Dune");
        m.renameWishlist(birthday, "Diwali");
        assertEquals("Dune", dune.getName());
        assertEquals("Diwali", birthday.getName());
    }

    @Test
    @DisplayName("a copy can only be put in a location that is in the collection")
    void setLocationRequiresKnownLocation() throws DuplicateItemException {
        final CollectionManager m = new CollectionManager();
        Location shelf = new Location("Shelf");
        m.addLocation(shelf);
        final Copy copy = m.addItem("Dune").getCopies().get(0);

        m.setLocation(copy, shelf);
        assertEquals(shelf.getId(), copy.getLocationId());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.setLocation(copy, new Location("Not added"));
            }
        });
        assertEquals(shelf.getId(), copy.getLocationId());

        m.setLocation(copy, null);
        assertTrue(copy.isUnshelved());
    }

    @Test
    @DisplayName("an item can only be filed under a category that is in the collection")
    void fileUnderRequiresKnownCategory() throws DuplicateItemException {
        final CollectionManager m = new CollectionManager();
        final Category fiction = new Category("Fiction");
        m.addCategory(fiction);
        final Book dune = m.addItem("Dune");

        m.fileUnder(dune, fiction);
        m.fileUnder(dune, fiction);
        assertEquals(1, dune.getCategoryIds().size());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.fileUnder(dune, new Category("Not added"));
            }
        });
        // A book that was never added cannot be filed either.
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.fileUnder(new Book("Stray"), fiction);
            }
        });
    }

    @Test
    @DisplayName("a book can only join a series that is in the collection")
    void setSeriesRequiresKnownSeries() throws DuplicateItemException {
        final CollectionManager m = new CollectionManager();
        Series discworld = new Series("Discworld");
        m.addSeries(discworld);
        final Book guards = m.addItem("Guards! Guards!");

        m.setSeries(guards, discworld);
        assertEquals(discworld.getId(), guards.getSeriesId());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.setSeries(guards, new Series("Not added"));
            }
        });
        m.setSeries(guards, null);
        assertNull(guards.getSeriesId());
    }

    // ------------------------------------------------------------------
    // Integrity check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a collection built through the manager has no integrity problems")
    void cleanCollectionPasses() throws Exception {
        CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        m.addCategory(fiction);
        Location shelf = new Location("Shelf");
        m.addLocation(shelf);
        Series series = new Series("Dune Chronicles");
        m.addSeries(series);
        Book dune = m.addItem(DUNE_ISBN, "Dune");
        m.fileUnder(dune, fiction);
        m.setLocation(dune.getCopies().get(0), shelf);
        m.setSeries(dune, series);

        assertTrue(m.checkIntegrity().isEmpty());
        assertNotNull(m.toCollectionData());
    }

    @Test
    @DisplayName("an ISBN duplicated by bypassing the manager stops the save, with one problem named")
    void bypassedDuplicateIsbnStopsSave() throws Exception {
        // The exact slip ADR 0006 describes: a direct setter call the
        // manager never saw. Without the check, the database would refuse
        // this save and every one after it.
        final CollectionManager m = new CollectionManager();
        m.addItem(DUNE_ISBN, "Dune");
        Book other = m.addItem(SECOND_ISBN, "Nineteen Eighty-Four");
        other.setIsbn(DUNE_ISBN);

        List<String> problems = m.checkIntegrity();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains(DUNE_ISBN));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        m.toCollectionData();
                    }
                });
        assertTrue(e.getMessage().contains("1 problem was found."));
        assertTrue(e.getMessage().contains(DUNE_ISBN));
    }

    @Test
    @DisplayName("every kind of bypassed rule is reported, all in one list")
    void everyProblemListed() throws Exception {
        final CollectionManager m = new CollectionManager();

        // Two sibling categories, then two top-level ones, made to clash.
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        Category fantasy = new Category("Fantasy");
        m.addCategory(fiction);
        m.addCategory(scifi, fiction);
        m.addCategory(fantasy, fiction);
        fantasy.setName("science FICTION");
        Category reference = new Category("Reference");
        m.addCategory(reference);
        reference.setName("fiction");

        // Two sibling locations made to clash.
        Location room = new Location("Bedroom");
        Location shelfA = new Location("Shelf A");
        Location shelfB = new Location("Shelf B");
        m.addLocation(room);
        m.addLocation(shelfA, room);
        m.addLocation(shelfB, room);
        shelfB.setName("Shelf A");

        // Two series and two wishlists made to clash.
        m.addSeries(new Series("Discworld"));
        Series other = new Series("Dune Chronicles");
        m.addSeries(other);
        other.setName("discworld");
        m.addWishlist(new Wishlist("Birthday"));
        Wishlist second = new Wishlist("Diwali");
        m.addWishlist(second);
        second.setName("BIRTHDAY");

        // References to things that are not in the collection.
        Book dune = m.addItem("Dune");
        dune.addCategoryId("missing-category");
        dune.getCopies().get(0).setLocationId("missing-location");
        dune.setSeriesId("missing-series");

        List<String> problems = m.checkIntegrity();
        assertEquals(8, problems.size());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        m.toCollectionData();
                    }
                });
        assertTrue(e.getMessage().contains("8 problems were found."));
        assertTrue(e.getMessage().contains("missing-location"));
        assertTrue(e.getMessage().contains("Shelf A"));
    }
}
