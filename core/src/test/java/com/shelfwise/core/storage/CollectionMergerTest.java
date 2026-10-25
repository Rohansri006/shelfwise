package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link CollectionMerger} and {@link ImportReport}. */
class CollectionMergerTest {

    private static final String NEHRU_ISBN = "9780143031055";

    /**
     * A small but complete collection: nested trees, a series, a wishlist,
     * a filed and shelved book, and one in the trash.
     *
     * @return the collection
     */
    private static CollectionManager sampleCollection() throws Exception {
        CollectionManager m = new CollectionManager();
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        m.addCategory(fiction);
        m.addCategory(scifi, fiction);
        Location study = new Location("Study");
        Location shelf = new Location("Shelf A");
        m.addLocation(study);
        m.addLocation(shelf, study);
        Series dune = new Series("Dune Chronicles", 6);
        m.addSeries(dune);
        Wishlist wishlist = new Wishlist("Birthday");
        m.addWishlist(wishlist);
        wishlist.addEntry(new WishlistEntry("Anathem"));

        Book book = m.addItem("9780441172719", "Dune");
        m.fileUnder(book, scifi);
        m.setLocation(book.getCopies().get(0), shelf);
        m.setSeries(book, dune);
        book.setSeriesPosition(1);
        m.moveToTrash(m.addItem("Binned").getId());
        return m;
    }

    /**
     * Exports a collection to JSON text and reads it straight back, which
     * is exactly what a file on disk would give.
     *
     * @param m the collection
     * @return the parsed import
     */
    private static ParsedImport viaJson(CollectionManager m) throws Exception {
        StringWriter out = new StringWriter();
        new JsonExporter().export(m.toCollectionData(), out);
        return new JsonImporter().read(new StringReader(out.toString()));
    }

    @Test
    @DisplayName("merging into an empty collection adds everything, with references pointing at the new nodes")
    void intoEmpty() throws Exception {
        CollectionManager target = new CollectionManager();

        ImportReport report = new CollectionMerger().merge(viaJson(sampleCollection()), target);

        assertEquals(2, report.getBooksAdded());
        assertEquals(2, report.getCategoriesCreated());
        assertEquals(2, report.getLocationsCreated());
        assertEquals(1, report.getSeriesAdded());
        assertEquals(1, report.getWishlistsAdded());
        assertEquals(1, report.getWishlistEntriesAdded());
        assertTrue(target.checkIntegrity().isEmpty());

        Book dune = target.findBookByIsbn("9780441172719");
        Category filed = target.findCategoryById(dune.getCategoryIds().get(0));
        assertEquals("Fiction > Science fiction", filed.getPath());
        Location shelved = target.findLocationById(dune.getCopies().get(0).getLocationId());
        assertEquals("Study > Shelf A", shelved.getPath());
        assertEquals("Dune Chronicles", target.findSeriesById(dune.getSeriesId()).getName());
        assertEquals(1, dune.getSeriesPosition());
        // The trashed book arrives in the trash.
        assertEquals(1, target.getTrash().size());
    }

    @Test
    @DisplayName("merging the same file twice changes nothing the second time")
    void mergeTwiceIsHarmless() throws Exception {
        CollectionManager source = sampleCollection();
        CollectionManager target = new CollectionManager();
        CollectionMerger merger = new CollectionMerger();
        merger.merge(viaJson(source), target);

        ImportReport second = merger.merge(viaJson(source), target);

        assertEquals(0, second.getBooksAdded());
        assertEquals(2, second.getSkippedBooks().size());
        assertEquals(0, second.getCategoriesCreated());
        assertEquals(0, second.getLocationsCreated());
        assertEquals(0, second.getSeriesAdded());
        assertEquals(0, second.getWishlistsAdded());
        assertEquals(0, second.getWishlistEntriesAdded());
        assertEquals(1, target.getItems().size());
        assertEquals(1, target.getWishlists().get(0).getEntryCount());
    }

    @Test
    @DisplayName("an incoming wishlist entry whose ISBN is already on that list is skipped, not added twice")
    void wishlistEntrySameIsbnSkipped() throws Exception {
        CollectionManager source = new CollectionManager();
        Wishlist incoming = new Wishlist("Birthday");
        source.addWishlist(incoming);
        WishlistEntry renamed = new WishlistEntry("Glimpses (paperback)");
        renamed.setIsbn("0-14-303105-8");
        incoming.addEntry(renamed);

        CollectionManager target = new CollectionManager();
        Wishlist mine = new Wishlist("Birthday");
        target.addWishlist(mine);
        WishlistEntry original = new WishlistEntry("Glimpses of World History");
        original.setIsbn("9780143031055");
        mine.addEntry(original);

        ImportReport report = new CollectionMerger().merge(viaJson(source), target);

        assertEquals(0, report.getWishlistEntriesAdded());
        assertEquals(1, mine.getEntryCount());
    }

    @Test
    @DisplayName("a CSV export merged back into its own collection is recognised by id")
    void csvMergeTwiceIsHarmless() throws Exception {
        CollectionManager m = sampleCollection();
        StringWriter out = new StringWriter();
        new CsvExporter().export(m.toCollectionData(), out);
        ParsedImport parsed = new CsvImporter().read(new StringReader(out.toString()));

        ImportReport report = new CollectionMerger().merge(parsed, m);

        assertEquals(0, report.getBooksAdded());
        assertEquals(1, report.getSkippedBooks().size());
        assertEquals(0, report.getCategoriesCreated());
        assertEquals(0, report.getLocationsCreated());
    }

    @Test
    @DisplayName("tree nodes match by path even when the ids differ, ignoring case")
    void treesMatchByPath() throws Exception {
        CollectionManager target = new CollectionManager();
        Category fiction = new Category("fiction");
        Category scifi = new Category("SCIENCE FICTION");
        target.addCategory(fiction);
        target.addCategory(scifi, fiction);

        ImportReport report = new CollectionMerger().merge(viaJson(sampleCollection()), target);

        assertEquals(0, report.getCategoriesCreated());
        Book dune = target.findBookByIsbn("9780441172719");
        assertSame(scifi, target.findCategoryById(dune.getCategoryIds().get(0)));
    }

    @Test
    @DisplayName("a book already present by ISBN is skipped, and its copies can be added to the existing book")
    void skippedByIsbnThenAddedAsCopies() throws Exception {
        CollectionManager source = new CollectionManager();
        Location loft = new Location("Loft");
        source.addLocation(loft);
        Book theirs = source.addItem(NEHRU_ISBN, "Glimpses of World History");
        source.setLocation(theirs.getCopies().get(0), loft);

        CollectionManager target = new CollectionManager();
        Book ours = target.addItem("0-14-303105-8", "Glimpses (my copy)");
        CollectionMerger merger = new CollectionMerger();

        ImportReport report = merger.merge(viaJson(source), target);

        assertEquals(0, report.getBooksAdded());
        ImportReport.SkippedBook skipped = report.getSkippedBooks().get(0);
        assertSame(ours, skipped.getExisting());

        assertEquals(1, merger.addAsCopies(skipped, target));
        assertEquals(2, ours.getCopyCount());
        Copy added = ours.getCopies().get(1);
        assertNotEquals(theirs.getCopies().get(0).getId(), added.getId());
        assertEquals("Loft", target.findLocationById(added.getLocationId()).getName());
        assertTrue(target.checkIntegrity().isEmpty());
    }

    @Test
    @DisplayName("series and wishlists match by name, and a wishlist gains only entries it lacks")
    void namedThingsMatchByName() throws Exception {
        CollectionManager target = new CollectionManager();
        Series existing = new Series("DUNE CHRONICLES");
        target.addSeries(existing);
        Wishlist birthday = new Wishlist("birthday");
        target.addWishlist(birthday);
        birthday.addEntry(new WishlistEntry("anathem"));

        CollectionManager source = sampleCollection();
        source.getWishlists().get(0).addEntry(new WishlistEntry("Seveneves"));

        ImportReport report = new CollectionMerger().merge(viaJson(source), target);

        assertEquals(0, report.getSeriesAdded());
        assertEquals(0, report.getWishlistsAdded());
        assertEquals(1, report.getWishlistEntriesAdded());
        assertEquals(2, birthday.getEntryCount());
        assertEquals(existing.getId(), target.findBookByIsbn("9780441172719").getSeriesId());
    }

    @Test
    @DisplayName("the summary gets singular and plural wording right")
    void summaryWording() throws Exception {
        CollectionManager source = new CollectionManager();
        source.addItem("Dune");
        CollectionManager target = new CollectionManager();
        CollectionMerger merger = new CollectionMerger();

        assertEquals("Added 1 book.", merger.merge(viaJson(source), target).getSummary());
        source.addItem("Anathem");
        assertEquals("Added 1 book; 1 was already in the collection.",
                merger.merge(viaJson(source), target).getSummary());
        assertEquals("Added 0 books; 2 were already in the collection.",
                merger.merge(viaJson(source), target).getSummary());
    }
}
