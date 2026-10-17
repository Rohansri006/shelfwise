package com.shelfwise.core.metadata;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.service.BookMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a lookup found, and where it came from.
 * <p>
 * Only ever a <strong>suggestion</strong>: it fills the Add form or a scan
 * row, the user may change anything, and nothing enters the collection
 * until they accept it. {@link #toBook} is that acceptance.
 * <p>
 * It records honestly which lookup sources supplied the details - the first
 * one that found the book, then any that filled gaps - and which sources
 * could not help and why, so the screen can say "Found on Open Library
 * (Google Books unavailable: rate limit reached)".
 */
public class LookupResult {

    private final String isbn;
    private final BookMetadata metadata;
    private final List<String> sources;
    private final List<String> unavailable;
    private final boolean fromCache;
    private final long millis;

    /**
     * @param isbn        the ISBN-13 looked up
     * @param metadata    the details found, merged across sources
     * @param sources     the sources that supplied them, the base first
     * @param unavailable why other sources could not help, e.g. "Google
     *                    Books: rate limit reached"
     * @param fromCache   true if served from the disk cache
     * @param millis      how long the lookup took
     */
    public LookupResult(String isbn, BookMetadata metadata, List<String> sources,
                        List<String> unavailable, boolean fromCache, long millis) {
        this.isbn = isbn;
        this.metadata = metadata;
        this.sources = new ArrayList<>(sources);
        this.unavailable = new ArrayList<>(unavailable);
        this.fromCache = fromCache;
        this.millis = millis;
    }

    /** @return the ISBN-13 that was looked up */
    public String getIsbn() {
        return isbn;
    }

    /** @return the details found */
    public BookMetadata getMetadata() {
        return metadata;
    }

    /** @return the sources that supplied details, the base source first */
    public List<String> getSources() {
        return Collections.unmodifiableList(sources);
    }

    /** @return why the other sources could not help; empty if none failed */
    public List<String> getUnavailable() {
        return Collections.unmodifiableList(unavailable);
    }

    /** @return true if the answer came from the disk cache */
    public boolean isFromCache() {
        return fromCache;
    }

    /** @return how long the lookup took, in milliseconds */
    public long getMillis() {
        return millis;
    }

    /**
     * A one-line, honest account of where the details came from.
     *
     * @return e.g. "Found on Open Library, gaps filled from Google Books"
     */
    public String describe() {
        StringBuilder text = new StringBuilder("Found on ").append(sources.get(0));
        if (sources.size() > 1) {
            text.append(", gaps filled from ").append(String.join(", ", sources.subList(1, sources.size())));
        }
        if (!unavailable.isEmpty()) {
            text.append(" (").append(String.join("; ", unavailable)).append(")");
        }
        if (fromCache) {
            text.append(" [from cache]");
        }
        return text.toString();
    }

    /**
     * Accepts the suggestion: builds a {@link Book} from it.
     * <p>
     * Every field the Add form shows is carried across, authors in the
     * source's order so the first becomes the primary author. A source can
     * be wrong, though - a year of 0201 or 3005 - and {@code Book} rejects
     * such values. Rather than fail the whole add over one bad field, that
     * field is left empty and a note explains why.
     * <p>
     * Lives here, not as a {@code Book} constructor, because {@code Book}
     * is in the model layer and {@link BookMetadata} in the service layer:
     * the model must not depend on the layer above it.
     *
     * @param notes receives a line for every field that had to be left
     *              empty; may be null if the caller does not want them
     * @return a new book, not yet added to any collection
     * @throws InvalidISBNException if the stored ISBN is invalid, which
     *                              cannot happen for a real lookup result
     */
    public Book toBook(List<String> notes) throws InvalidISBNException {
        Book book = new Book(isbn, metadata.getTitle());
        book.setSubtitle(metadata.getSubtitle());
        List<String> authors = metadata.getAuthors();
        for (int i = 0; i < authors.size(); i++) {
            book.addContributor(new Contributor(authors.get(i)));
        }
        book.setPublisher(metadata.getPublisher());
        book.setLanguage(metadata.getLanguage());
        try {
            book.setPublicationYear(metadata.getYear());
        } catch (IllegalArgumentException e) {
            note(notes, "Year " + metadata.getYear() + " left empty: " + e.getMessage());
        }
        try {
            book.setPages(metadata.getPages());
        } catch (IllegalArgumentException e) {
            note(notes, "Pages left empty: " + e.getMessage());
        }
        return book;
    }

    /**
     * @param notes the list to add to, or null
     * @param note  the line to add
     */
    private static void note(List<String> notes, String note) {
        if (notes != null) {
            notes.add(note);
        }
    }
}
