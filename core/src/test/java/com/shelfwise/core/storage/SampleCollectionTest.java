package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.service.CollectionData;
import com.shelfwise.core.service.CollectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the bundled sample collection. The demo depends on it, so it
 * must load cleanly and give every screen something to show (D23).
 */
class SampleCollectionTest {

    @TempDir
    Path folder;

    /**
     * @return a fresh collection holding the whole sample
     */
    private static CollectionManager loadedSample() throws Exception {
        CollectionManager manager = new CollectionManager();
        new CollectionMerger().merge(SampleCollection.read(), manager);
        return manager;
    }

    @Test
    @DisplayName("the sample reads with no problems: 25 books plus one in the trash, priced in INR")
    void readsCleanly() throws Exception {
        ParsedImport sample = SampleCollection.read();

        // A problem here would mean an invalid ISBN, date or value in the file.
        assertTrue(sample.getProblems().isEmpty(), sample.getProblems().toString());
        assertEquals(26, sample.getBookCount());
        assertEquals("INR", sample.getCurrencyCode());

        CollectionManager manager = loadedSample();
        assertEquals(25, manager.getItems().size());
        assertEquals(1, manager.getTrash().size());
        assertTrue(manager.checkIntegrity().isEmpty());
    }

    @Test
    @DisplayName("every screen has data: nested trees, a series, wishlists, copies and reading history")
    void everyScreenHasData() throws Exception {
        CollectionManager m = loadedSample();

        // Organise: both trees nested at least two levels deep.
        assertEquals(3, m.getRootCategories().size());
        assertEquals(3, m.getRootLocations().size());
        Location row = m.findLocationById(
                m.findBookByIsbn("9780143031055").getCopies().get(0).getLocationId());
        assertEquals("Study > Shelf A > Row 1", row.getPath());

        // Series & wishlists.
        Series lotr = m.getSeries().get(0);
        assertEquals("The Lord of the Rings", lotr.getName());
        assertEquals(lotr.getId(), m.findBookByIsbn("9780547928197").getSeriesId());
        assertEquals(3, m.findBookByIsbn("9780547928197").getSeriesPosition());
        assertEquals(2, m.getWishlists().size());
        Wishlist toBuy = m.getWishlists().get(0);
        assertEquals(2, toBuy.getEntryCount());

        // Browse filters: every reading status is represented.
        assertEquals(2, m.search(ReadingStatus.READING).size());
        assertEquals(1, m.search(ReadingStatus.ABANDONED).size());
        assertTrue(m.search(ReadingStatus.UNREAD).size() > 0);
        assertTrue(m.search(ReadingStatus.FINISHED).size() > 0);

        // Copies: one book owns two, and some copies are not on a shelf.
        assertEquals(2, m.findBookByIsbn("9780547928227").getCopyCount());
        int unshelved = 0;
        List<CollectionItem> items = m.getItems();
        for (int i = 0; i < items.size(); i++) {
            List<Copy> copies = ((Book) items.get(i)).getCopies();
            for (int j = 0; j < copies.size(); j++) {
                if (copies.get(j).isUnshelved()) {
                    unshelved++;
                }
            }
        }
        assertEquals(2, unshelved);

        // Custom fields, read twice for the Dune re-read.
        assertNotNull(m.findBookByIsbn("9788173711466").getCustomField("signed"));
        assertEquals(2, m.findBookByIsbn("9780441172719").getTimesRead());
    }

    @Test
    @DisplayName("the sample saves to a real SQLite database and loads back whole")
    void survivesTheDatabase() throws Exception {
        // The first-run path end to end: read the bundled file, merge it,
        // save through the same storage the app uses, load it again.
        SqliteStorage storage = new SqliteStorage(folder);
        storage.save(loadedSample().toCollectionData());

        CollectionData loaded = storage.load();
        CollectionManager again = new CollectionManager(loaded);

        assertEquals(25, again.getItems().size());
        assertEquals(1, again.getTrash().size());
        assertEquals(11, loaded.getCategories().size());
        assertEquals(11, loaded.getLocations().size());
        assertEquals(2, again.getWishlists().size());
        assertTrue(again.checkIntegrity().isEmpty());
    }
}
