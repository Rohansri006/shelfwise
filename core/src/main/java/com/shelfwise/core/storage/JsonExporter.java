package com.shelfwise.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes the whole collection as JSON: everything, including the trash,
 * both trees, series, wishlists, reading history, custom fields and the
 * currency (D16). This is the format that loses nothing, so it doubles as
 * a portable backup and as the format of the bundled sample collection.
 * <p>
 * The model is first copied into a {@link JsonCollection}, a set of plain
 * fields, and Gson turns that into text. {@link JsonCollection} explains
 * why the model is not handed to Gson directly.
 */
public class JsonExporter extends Exporter {

    /** Indented output, so the file can be read and diffed by a person. */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** @return "json" */
    @Override
    public String getFileExtension() {
        return "json";
    }

    /**
     * @param data the collection to write
     * @param out  an open, buffered writer
     * @throws IOException if writing fails
     */
    @Override
    protected void write(CollectionData data, BufferedWriter out) throws IOException {
        gson.toJson(toJson(data), out);
        out.newLine();
    }

    /**
     * Copies the model into the file's shape. Package-private so a test can
     * look at the result without parsing text.
     *
     * @param data the collection
     * @return the same collection as plain fields
     */
    JsonCollection toJson(CollectionData data) {
        JsonCollection json = new JsonCollection();
        json.formatVersion = JsonCollection.CURRENT_VERSION;
        json.exportedAt = Instant.now().toString();
        json.currency = data.getCurrencyCode();

        for (int i = 0; i < data.getBooks().size(); i++) {
            json.books.add(toJson(data.getBooks().get(i)));
        }
        for (int i = 0; i < data.getCategories().size(); i++) {
            Category category = data.getCategories().get(i);
            json.categories.add(node(category.getId(), category.getName(), category.getParentId()));
        }
        for (int i = 0; i < data.getLocations().size(); i++) {
            Location location = data.getLocations().get(i);
            json.locations.add(node(location.getId(), location.getName(), location.getParentId()));
        }
        for (int i = 0; i < data.getSeries().size(); i++) {
            Series series = data.getSeries().get(i);
            JsonCollection.SeriesJson s = new JsonCollection.SeriesJson();
            s.id = series.getId();
            s.name = series.getName();
            s.totalBooks = series.getTotalBooks();
            json.series.add(s);
        }
        for (int i = 0; i < data.getWishlists().size(); i++) {
            json.wishlists.add(toJson(data.getWishlists().get(i)));
        }
        return json;
    }

    /**
     * An overload of {@code toJson} for one book: the parameter type picks
     * the version at compile time.
     *
     * @param book the book
     * @return the book as plain fields
     */
    private JsonCollection.BookJson toJson(Book book) {
        JsonCollection.BookJson b = new JsonCollection.BookJson();
        b.id = book.getId();
        b.title = book.getTitle();
        b.subtitle = book.getSubtitle();
        b.isbn = book.getIsbn();
        b.publisher = book.getPublisher();
        b.publicationYear = book.getPublicationYear();
        b.language = book.getLanguage();
        b.pages = book.getPages();
        b.seriesId = book.getSeriesId();
        b.seriesPosition = book.getSeriesPosition();
        b.readingStatus = book.getReadingStatus().name();
        b.rating = book.getRating();
        b.notes = book.getNotes();
        b.deleted = book.isDeleted();
        b.dateAdded = dateText(book.getDateAdded());
        b.categoryIds.addAll(book.getCategoryIds());

        List<Contributor> contributors = book.getContributors();
        for (int i = 0; i < contributors.size(); i++) {
            JsonCollection.ContributorJson c = new JsonCollection.ContributorJson();
            c.name = contributors.get(i).getName();
            c.role = contributors.get(i).getRole().name();
            b.contributors.add(c);
        }
        List<Copy> copies = book.getCopies();
        for (int i = 0; i < copies.size(); i++) {
            Copy copy = copies.get(i);
            JsonCollection.CopyJson c = new JsonCollection.CopyJson();
            c.id = copy.getId();
            c.locationId = copy.getLocationId();
            c.format = copy.getFormat().name();
            c.condition = copy.getCondition().name();
            c.purchaseDate = dateText(copy.getPurchaseDate());
            c.pricePaid = copy.getPricePaid();
            b.copies.add(c);
        }
        List<ReadingSession> sessions = book.getReadingSessions();
        for (int i = 0; i < sessions.size(); i++) {
            ReadingSession session = sessions.get(i);
            JsonCollection.SessionJson s = new JsonCollection.SessionJson();
            s.id = session.getId();
            s.startDate = dateText(session.getStartDate());
            s.endDate = dateText(session.getEndDate());
            s.pagesRead = session.getPagesRead();
            s.percentComplete = session.getPercentComplete();
            b.readingSessions.add(s);
        }
        // Walk the keys in order; the model's LinkedHashMap keeps the order
        // the user added the fields in.
        Map<String, String> fields = book.getCustomFields();
        List<String> keys = new ArrayList<>(fields.keySet());
        for (int i = 0; i < keys.size(); i++) {
            JsonCollection.FieldJson f = new JsonCollection.FieldJson();
            f.key = keys.get(i);
            f.value = fields.get(keys.get(i));
            b.customFields.add(f);
        }
        return b;
    }

    /**
     * Another overload, for one wishlist and its entries.
     *
     * @param wishlist the wishlist
     * @return the wishlist as plain fields
     */
    private JsonCollection.WishlistJson toJson(Wishlist wishlist) {
        JsonCollection.WishlistJson w = new JsonCollection.WishlistJson();
        w.id = wishlist.getId();
        w.name = wishlist.getName();
        List<WishlistEntry> entries = wishlist.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            WishlistEntry entry = entries.get(i);
            JsonCollection.EntryJson e = new JsonCollection.EntryJson();
            e.id = entry.getId();
            e.title = entry.getTitle();
            e.isbn = entry.getIsbn();
            e.author = entry.getAuthor();
            e.priority = entry.getPriority().name();
            e.expectedPrice = entry.getExpectedPrice();
            e.store = entry.getStore();
            e.rare = entry.isRare();
            w.entries.add(e);
        }
        return w;
    }

    /**
     * @param id       the node's id
     * @param name     its name
     * @param parentId its parent's id, or null for a top-level node
     * @return the node as plain fields
     */
    private static JsonCollection.NodeJson node(String id, String name, String parentId) {
        JsonCollection.NodeJson n = new JsonCollection.NodeJson();
        n.id = id;
        n.name = name;
        n.parentId = parentId;
        return n;
    }

    /**
     * @param date a date, or null
     * @return it in ISO form ("2024-03-09"), or null
     */
    private static String dateText(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
