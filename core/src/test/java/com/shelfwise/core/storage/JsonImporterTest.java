package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Priority;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link JsonImporter}, mostly as round trips through {@link JsonExporter}. */
class JsonImporterTest {

    @TempDir
    Path folder;

    /**
     * @param json the file's text
     * @return what the importer made of it
     */
    private static ParsedImport read(String json) throws IOException {
        return new JsonImporter().read(new StringReader(json));
    }

    @Test
    @DisplayName("a full export reads back with ids, trees, trash, history and currency intact")
    void roundTrip() throws Exception {
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        fiction.addChild(scifi);
        Location study = new Location("Study");
        Location shelf = new Location("Shelf A");
        study.addChild(shelf);
        Series dune = new Series("Dune Chronicles", 6);

        Book book = new Book("9780143031055", "Dune");
        book.addContributor(new Contributor("Frank Herbert"));
        book.addContributor(new Contributor("Someone", ContributorRole.TRANSLATOR));
        book.addCategoryId(scifi.getId());
        book.setSeriesId(dune.getId());
        book.setSeriesPosition(1);
        book.getCopies().get(0).setLocationId(shelf.getId());
        book.setCustomField("Signed", "yes");
        ReadingSession session = new ReadingSession(LocalDate.of(2024, 1, 5));
        book.addReadingSession(session);
        book.finishReadingSession(session, LocalDate.of(2024, 2, 1));
        // Abandoned on a re-read: must not come back as FINISHED, which is
        // what re-adding the finished session would otherwise set.
        book.setReadingStatus(ReadingStatus.ABANDONED);
        Book trashed = new Book("Binned");
        trashed.setDeleted(true);

        Wishlist wishlist = new Wishlist("Birthday");
        WishlistEntry entry = new WishlistEntry("Anathem");
        entry.setPriority(Priority.HIGH);
        wishlist.addEntry(entry);

        List<Book> books = new ArrayList<>();
        books.add(book);
        books.add(trashed);
        List<Category> categories = new ArrayList<>();
        categories.add(fiction);
        categories.add(scifi);
        List<Location> locations = new ArrayList<>();
        locations.add(study);
        locations.add(shelf);
        List<Series> series = new ArrayList<>();
        series.add(dune);
        List<Wishlist> wishlists = new ArrayList<>();
        wishlists.add(wishlist);
        CollectionData original = new CollectionData(books, categories, locations, series,
                wishlists, null, "USD");

        // Through a real file this time, as the Export menu would.
        Path file = folder.resolve("collection.json");
        new JsonExporter().export(original, file);
        ParsedImport parsed = new JsonImporter().read(file);

        assertTrue(parsed.getProblems().isEmpty());
        assertEquals("USD", parsed.getCurrencyCode());
        CollectionData data = parsed.getData();
        assertEquals(2, data.getBooks().size());
        Book back = data.getBooks().get(0);
        assertEquals(book.getId(), back.getId());
        assertEquals(book.getCopies().get(0).getId(), back.getCopies().get(0).getId());
        assertEquals(ReadingStatus.ABANDONED, back.getReadingStatus());
        assertEquals(1, back.getTimesRead());
        assertEquals(ContributorRole.TRANSLATOR, back.getContributors().get(1).getRole());
        assertEquals(dune.getId(), back.getSeriesId());
        assertEquals(1, back.getSeriesPosition());
        assertEquals("yes", back.getCustomField("signed"));
        assertTrue(data.getBooks().get(1).isDeleted());

        assertEquals("Fiction > Science fiction", data.getCategories().get(1).getPath());
        assertEquals("Study > Shelf A", data.getLocations().get(1).getPath());
        assertEquals("Dune Chronicles", data.getSeries().get(0).getName());
        assertEquals(Priority.HIGH, data.getWishlists().get(0).getEntries().get(0).getPriority());
    }

    @Test
    @DisplayName("a broken book is reported and skipped, and the rest of the file is read")
    void badBookSkipped() throws Exception {
        String json = "{\"formatVersion\":1,\"currency\":\"INR\",\"books\":["
                + "{\"id\":\"a\",\"title\":\"Good\",\"copies\":[{\"id\":\"c1\"}]},"
                + "{\"id\":\"b\",\"title\":\"Bad ISBN\",\"isbn\":\"9780143031056\","
                + "\"copies\":[{\"id\":\"c2\"}]},"
                + "{\"id\":\"c\",\"title\":\"No copies\"}]}";

        ParsedImport parsed = read(json);

        assertEquals(1, parsed.getBookCount());
        assertEquals(2, parsed.getProblems().size());
        assertTrue(parsed.getProblems().get(0).startsWith("Book \"Bad ISBN\":"));
        assertTrue(parsed.getProblems().get(1).startsWith("Book \"No copies\":"));
    }

    @Test
    @DisplayName("a hand-written file may leave out optional values")
    void minimalFile() throws Exception {
        // No copy id, no format, no status, no priority: defaults apply
        // instead of a crash.
        String json = "{\"books\":[{\"id\":\"a\",\"title\":\"Godaan\",\"copies\":[{}]}],"
                + "\"wishlists\":[{\"id\":\"w\",\"name\":\"Someday\","
                + "\"entries\":[{\"id\":\"e\",\"title\":\"Anathem\"}]}]}";

        ParsedImport parsed = read(json);

        assertTrue(parsed.getProblems().isEmpty());
        Book book = parsed.getData().getBooks().get(0);
        assertNotNull(book.getCopies().get(0).getId());
        assertEquals(ReadingStatus.UNREAD, book.getReadingStatus());
        assertEquals("INR", parsed.getCurrencyCode());
        assertEquals(Priority.MEDIUM,
                parsed.getData().getWishlists().get(0).getEntries().get(0).getPriority());
    }

    @Test
    @DisplayName("text that is not JSON, an empty file, or a newer format is refused")
    void unreadableFiles() {
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                read("title,isbn\nDune,123\n");
            }
        });
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                read("");
            }
        });
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                read("{\"formatVersion\":99}");
            }
        });
    }
}
