package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a merge did, for the screen to show afterwards: how much was added,
 * which books were already present, and what could not be read at all.
 * <p>
 * Filled in by {@link CollectionMerger} as it goes; read-only to everyone
 * else, since the setters are package-private.
 */
public class ImportReport {

    /**
     * A book from the file that the collection already had, by id or ISBN
     * (ADR 0004). Both are kept so the screen can say "already have Dune"
     * and offer to add the file's copies to the existing book instead
     * (D17), through {@link CollectionMerger#addAsCopies}.
     */
    public static class SkippedBook {

        private final Book incoming;
        private final Book existing;

        SkippedBook(Book incoming, Book existing) {
            this.incoming = incoming;
            this.existing = existing;
        }

        /** @return the book as the file described it, never added */
        public Book getIncoming() {
            return incoming;
        }

        /** @return the book already in the collection */
        public Book getExisting() {
            return existing;
        }
    }

    private int booksAdded;
    private int categoriesCreated;
    private int locationsCreated;
    private int seriesAdded;
    private int wishlistsAdded;
    private int wishlistEntriesAdded;
    private final List<SkippedBook> skippedBooks = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    /** @return how many books were added */
    public int getBooksAdded() {
        return booksAdded;
    }

    /** @return how many category nodes did not exist and were created */
    public int getCategoriesCreated() {
        return categoriesCreated;
    }

    /** @return how many location nodes did not exist and were created */
    public int getLocationsCreated() {
        return locationsCreated;
    }

    /** @return how many series were new */
    public int getSeriesAdded() {
        return seriesAdded;
    }

    /** @return how many wishlists were new */
    public int getWishlistsAdded() {
        return wishlistsAdded;
    }

    /** @return how many entries were added, to new and existing wishlists */
    public int getWishlistEntriesAdded() {
        return wishlistEntriesAdded;
    }

    /** @return a read-only list of the books the collection already had */
    public List<SkippedBook> getSkippedBooks() {
        return Collections.unmodifiableList(skippedBooks);
    }

    /** @return a read-only list of everything that could not be read or added */
    public List<String> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    /**
     * A one-line summary with singular and plural wording right, e.g.
     * "Added 1 book; 2 were already in the collection."
     *
     * @return the summary
     */
    public String getSummary() {
        String summary = "Added " + booksAdded + (booksAdded == 1 ? " book" : " books");
        int skipped = skippedBooks.size();
        if (skipped > 0) {
            summary += "; " + skipped + (skipped == 1 ? " was" : " were")
                    + " already in the collection";
        }
        if (!problems.isEmpty()) {
            summary += "; " + problems.size()
                    + (problems.size() == 1 ? " problem" : " problems");
        }
        return summary + ".";
    }

    void bookAdded() {
        booksAdded++;
    }

    void categoryCreated() {
        categoriesCreated++;
    }

    void locationCreated() {
        locationsCreated++;
    }

    void seriesAdded() {
        seriesAdded++;
    }

    void wishlistAdded() {
        wishlistsAdded++;
    }

    void wishlistEntryAdded() {
        wishlistEntriesAdded++;
    }

    void bookSkipped(Book incoming, Book existing) {
        skippedBooks.add(new SkippedBook(incoming, existing));
    }

    void problem(String description) {
        problems.add(description);
    }
}
