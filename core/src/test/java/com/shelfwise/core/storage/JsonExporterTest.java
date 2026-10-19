package com.shelfwise.core.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link JsonExporter}. */
class JsonExporterTest {

    /**
     * Exports and parses the text back into a tree of JSON objects, so the
     * tests check the real output rather than the DTO.
     *
     * @param data what to export
     * @return the parsed root object
     */
    private static JsonObject export(CollectionData data) throws Exception {
        StringWriter out = new StringWriter();
        new JsonExporter().export(data, out);
        return JsonParser.parseString(out.toString()).getAsJsonObject();
    }

    @Test
    @DisplayName("everything is exported, the trash and currency included")
    void exportsEverything() throws Exception {
        Book live = new Book("Dune");
        Book trashed = new Book("Binned");
        trashed.setDeleted(true);
        List<Book> books = new ArrayList<>();
        books.add(live);
        books.add(trashed);
        List<Series> series = new ArrayList<>();
        series.add(new Series("Dune Chronicles", 6));
        Wishlist wishlist = new Wishlist("Birthday");
        wishlist.addEntry(new WishlistEntry("Anathem"));
        List<Wishlist> wishlists = new ArrayList<>();
        wishlists.add(wishlist);

        JsonObject root = export(new CollectionData(books, null, null, series, wishlists, null, "GBP"));

        assertEquals(JsonCollection.CURRENT_VERSION, root.get("formatVersion").getAsInt());
        assertEquals("GBP", root.get("currency").getAsString());
        assertEquals(2, root.getAsJsonArray("books").size());
        assertTrue(root.getAsJsonArray("books").get(1).getAsJsonObject().get("deleted").getAsBoolean());
        assertEquals("Dune Chronicles",
                root.getAsJsonArray("series").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Anathem", root.getAsJsonArray("wishlists").get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    @DisplayName("a category tree is written flat, each node naming only its parent's id")
    void treeIsFlat() throws Exception {
        // If children were written inside parents and parents inside
        // children, the output would never end.
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        fiction.addChild(scifi);
        List<Category> categories = new ArrayList<>();
        categories.add(fiction);
        categories.add(scifi);

        JsonArray nodes = export(new CollectionData(null, categories, null, null, null, null))
                .getAsJsonArray("categories");

        assertEquals(2, nodes.size());
        assertFalse(nodes.get(0).getAsJsonObject().has("parentId"));
        assertEquals(fiction.getId(), nodes.get(1).getAsJsonObject().get("parentId").getAsString());
        assertFalse(nodes.get(0).getAsJsonObject().has("children"));
    }

    @Test
    @DisplayName("dates are ISO strings and custom fields keep their order")
    void datesAndFields() throws Exception {
        Book book = new Book("Dune");
        ReadingSession session = new ReadingSession(LocalDate.of(2024, 1, 5));
        book.addReadingSession(session);
        book.finishReadingSession(session, LocalDate.of(2024, 2, 1));
        book.setCustomField("Signed", "yes");
        book.setCustomField("Acquired at", "Daryaganj market");
        List<Book> books = new ArrayList<>();
        books.add(book);

        JsonObject b = export(new CollectionData(books, null, null, null, null, null))
                .getAsJsonArray("books").get(0).getAsJsonObject();

        JsonObject s = b.getAsJsonArray("readingSessions").get(0).getAsJsonObject();
        assertEquals("2024-01-05", s.get("startDate").getAsString());
        assertEquals("2024-02-01", s.get("endDate").getAsString());
        assertEquals("FINISHED", b.get("readingStatus").getAsString());
        JsonArray fields = b.getAsJsonArray("customFields");
        assertEquals("Signed", fields.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("Acquired at", fields.get(1).getAsJsonObject().get("key").getAsString());
    }

    @Test
    @DisplayName("each exporter names its own file extension")
    void extensions() {
        // Called through the abstract type: the real class decides.
        Exporter csv = new CsvExporter();
        Exporter json = new JsonExporter();
        assertEquals("csv", csv.getFileExtension());
        assertEquals("json", json.getFileExtension());
    }
}
