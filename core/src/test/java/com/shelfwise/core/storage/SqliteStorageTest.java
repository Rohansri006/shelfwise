package com.shelfwise.core.storage;

import com.shelfwise.core.exception.SyncConflictException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link SqliteStorage}, end to end against a real file. */
class SqliteStorageTest {

    @TempDir
    Path folder;

    /** @return a small but complete collection: every table gets rows */
    private static CollectionData sampleData(String currency) {
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        fiction.addChild(scifi);
        List<Category> categories = new ArrayList<>();
        categories.add(scifi);
        categories.add(fiction);

        Location shelf = Location.createShelfWithRows("Study shelf", 2);
        List<Location> locations = new ArrayList<>(shelf.getSubtree());

        Series dunes = new Series("Dune Chronicles", 6);
        List<Series> series = new ArrayList<>();
        series.add(dunes);

        Book dune = new Book("Dune");
        dune.addContributor(new Contributor("Frank Herbert"));
        dune.addCategoryId(scifi.getId());
        dune.getCopies().get(0).setLocationId(shelf.getChildren().get(1).getId());
        dune.setSeriesId(dunes.getId());
        dune.setSeriesPosition(1);
        List<Book> books = new ArrayList<>();
        books.add(dune);

        Wishlist wishlist = new Wishlist("Birthday ideas");
        wishlist.addEntry(new WishlistEntry("Neuromancer"));
        List<Wishlist> wishlists = new ArrayList<>();
        wishlists.add(wishlist);

        return new CollectionData(books, categories, locations, series, wishlists, null, currency);
    }

    @Test
    @DisplayName("with no file yet, nothing exists and load returns an empty collection")
    void firstRun() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.resolve("data"));
        assertFalse(storage.dataExists());
        CollectionData data = storage.load();
        assertTrue(data.getBooks().isEmpty());
        assertEquals("INR", data.getCurrencyCode());
        // Loading must not create the file: first run is still first run.
        assertFalse(storage.dataExists());
    }

    @Test
    @DisplayName("a saved collection loads back complete, in a new storage object")
    void roundTrip() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder);
        storage.save(sampleData("USD"));
        assertTrue(storage.dataExists());

        CollectionData back = new SqliteStorage(folder).load();

        assertEquals(1, back.getBooks().size());
        assertEquals(2, back.getCategories().size());
        assertEquals(3, back.getLocations().size());
        assertEquals(1, back.getSeries().size());
        assertEquals(1, back.getWishlists().get(0).getEntryCount());
        assertEquals("USD", back.getCurrencyCode());
        assertNotNull(back.getSavedAt());
        Book dune = back.getBooks().get(0);
        assertEquals("Frank Herbert", dune.getPrimaryAuthor().getName());
        assertEquals(1, dune.getSeriesPosition());
    }

    @Test
    @DisplayName("saving again replaces the collection instead of adding to it")
    void saveReplaces() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder);
        storage.save(sampleData("INR"));
        storage.save(sampleData("INR"));
        // The second snapshot has new ids; a full rewrite leaves only it.
        assertEquals(1, storage.load().getBooks().size());
    }

    @Test
    @DisplayName("a save that fails part-way leaves the previous collection untouched")
    void failedSaveRollsBack() throws Exception {
        final SqliteStorage storage = new SqliteStorage(folder);
        storage.save(sampleData("INR"));

        // A book filed under a category that is not in the snapshot: the
        // foreign key fails on insert, after every delete has already run.
        Book broken = new Book("Broken");
        broken.addCategoryId("no-such-category");
        List<Book> books = new ArrayList<>();
        books.add(broken);
        final CollectionData bad = new CollectionData(books, null, null, null, null, null);

        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                storage.save(bad);
            }
        });

        CollectionData after = storage.load();
        assertEquals("Dune", after.getBooks().get(0).getTitle());
        assertEquals(2, after.getCategories().size());
    }

    @Test
    @DisplayName("a file changed by something else is not overwritten")
    void conflictDetected() throws Exception {
        final SqliteStorage storage = new SqliteStorage(folder);
        storage.save(sampleData("INR"));

        // Simulate another device writing the synced file.
        Files.setLastModifiedTime(storage.getDatabaseFile(),
                FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));

        SyncConflictException e = assertThrows(SyncConflictException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                storage.save(sampleData("INR"));
            }
        });
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), e.getFileModifiedAt());
    }

    @Test
    @DisplayName("after a conflict, rebaseline lets the next save go ahead")
    void rebaselineResolves() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder);
        storage.save(sampleData("INR"));
        Files.setLastModifiedTime(storage.getDatabaseFile(),
                FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));

        storage.rebaseline();
        storage.save(sampleData("EUR"));

        assertEquals("EUR", storage.load().getCurrencyCode());
    }

    @Test
    @DisplayName("a new storage object cannot overwrite an existing file it never loaded")
    void neverLoadedIsConflict() throws Exception {
        new SqliteStorage(folder).save(sampleData("INR"));
        final SqliteStorage fresh = new SqliteStorage(folder);
        assertThrows(SyncConflictException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                fresh.save(sampleData("INR"));
            }
        });
    }

    @Test
    @DisplayName("saving 1,000 books takes under 200 ms, so autosave can stay on the UI thread")
    void thousandBooksIsFast() throws Exception {
        // Decision D6: autosave runs on the JavaFX thread. That is only
        // acceptable if a realistic save is too quick to notice; this test
        // measures it rather than assuming it.
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Book book = new Book("Book " + i);
            book.addContributor(new Contributor("Author " + (i % 50)));
            books.add(book);
        }
        CollectionData data = new CollectionData(books, null, null, null, null, null);
        SqliteStorage storage = new SqliteStorage(folder);
        storage.save(data); // warm-up: loads the driver and creates the schema

        long start = System.nanoTime();
        storage.save(data);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 200, "1,000-book save took " + millis + " ms");
    }
}
