package com.shelfwise.core.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a ShelfWise JSON file: plain fields that Gson copies to and
 * from text by reflection, with no logic of its own (a data transfer
 * object, or DTO).
 * <p>
 * <strong>Why not hand the model classes to Gson directly?</strong> Three
 * reasons, each a real failure rather than a matter of taste (D19):
 * <ol>
 *   <li>A {@code Category} holds its parent and its children. Gson would
 *       follow child, then parent, then child again forever. Here a node
 *       holds only its {@code parentId}, so the tree is flat and finite.</li>
 *   <li>Gson has no built-in support for {@code LocalDate} and would get
 *       dates wrong silently (CLAUDE.md §8). Here every date is an ISO
 *       string such as "2024-03-09", which {@code LocalDate.parse} reads
 *       back.</li>
 *   <li>Model setters validate. Gson would bypass them by writing fields
 *       directly. Converting through the real constructors and setters on
 *       import keeps every rule in force.</li>
 * </ol>
 * Enum values are stored by name ("LIKE_NEW"), not label, because this file
 * is for programs, not people, and names never change with wording.
 * Package-private: only the JSON exporter and importer use it.
 */
class JsonCollection {

    /** Raised if the layout ever changes, so an old file can be recognised. */
    static final int CURRENT_VERSION = 1;

    int formatVersion;
    String exportedAt;
    String currency;
    List<BookJson> books = new ArrayList<>();
    List<NodeJson> categories = new ArrayList<>();
    List<NodeJson> locations = new ArrayList<>();
    List<SeriesJson> series = new ArrayList<>();
    List<WishlistJson> wishlists = new ArrayList<>();

    /** One book, with everything it owns. */
    static class BookJson {
        String id;
        String title;
        String subtitle;
        String isbn;
        String publisher;
        int publicationYear;
        String language;
        int pages;
        String seriesId;
        int seriesPosition;
        String readingStatus;
        int rating;
        String notes;
        boolean deleted;
        String dateAdded;
        List<String> categoryIds = new ArrayList<>();
        List<ContributorJson> contributors = new ArrayList<>();
        List<CopyJson> copies = new ArrayList<>();
        List<SessionJson> readingSessions = new ArrayList<>();
        /** A list rather than a map, so the order is explicit in the file. */
        List<FieldJson> customFields = new ArrayList<>();
    }

    /** A contributor, embedded in its book. */
    static class ContributorJson {
        String name;
        String role;
    }

    /** One physical copy. */
    static class CopyJson {
        String id;
        String locationId;
        String format;
        String condition;
        String purchaseDate;
        double pricePaid;
    }

    /** One reading of a book. */
    static class SessionJson {
        String id;
        String startDate;
        String endDate;
        int pagesRead;
        int percentComplete;
    }

    /** One custom field. */
    static class FieldJson {
        String key;
        String value;
    }

    /** A category or location node; the tree is rebuilt from parentId. */
    static class NodeJson {
        String id;
        String name;
        String parentId;
    }

    /** A series. */
    static class SeriesJson {
        String id;
        String name;
        int totalBooks;
    }

    /** A wishlist with its entries. */
    static class WishlistJson {
        String id;
        String name;
        List<EntryJson> entries = new ArrayList<>();
    }

    /** One wishlist entry. */
    static class EntryJson {
        String id;
        String title;
        String isbn;
        String author;
        String priority;
        double expectedPrice;
        String store;
        boolean rare;
    }
}
