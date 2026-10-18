package com.shelfwise.core.metadata;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.service.BookMetadata;
import com.shelfwise.core.service.MetadataProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Looks a book up by ISBN across several lookup sources, in order (ADR 0008).
 * <p>
 * <strong>The rules.</strong> The first source that finds the book gives the
 * base result. If that result is missing a field the Add form shows -
 * authors, publisher, year, pages or language - the next source is asked,
 * and it may only fill the empty fields; a value already found is never
 * replaced. A source that fails along the way is noted, not fatal: only
 * when no source finds the book at all is the lookup a failure.
 * <p>
 * <strong>Runtime polymorphism.</strong> The sources are held as
 * {@code List<MetadataProvider>}. The loop calls {@code fetchByIsbn} through
 * that interface type and never knows which class it is talking to; Java
 * picks Open Library's or Google Books' version at runtime. A third source
 * would need one new class and one line where the list is built.
 * <p>
 * <strong>Blocks.</strong> Each source can wait several seconds on the
 * network, so the desktop app calls this from a background thread, never
 * the JavaFX thread. This class itself starts no threads.
 */
public class MetadataLookup {

    /** Name used when every source has failed. */
    static final String ALL_SOURCES = "All lookup sources";

    private final List<MetadataProvider> sources;
    private final MetadataCache cache;

    /**
     * @param sources the lookup sources, in the order to try them; at least
     *                one
     * @param cache   where results are remembered, or null for no cache
     * @throws IllegalArgumentException if there are no sources
     */
    public MetadataLookup(List<MetadataProvider> sources, MetadataCache cache) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("A lookup needs at least one source.");
        }
        this.sources = new ArrayList<>(sources);
        this.cache = cache;
    }

    /**
     * Looks a book up, answering from the cache when it can.
     *
     * @param isbn the ISBN as typed or scanned, 10 or 13 digits
     * @return what was found and where from
     * @throws InvalidISBNException   if the ISBN is not valid; no source is
     *                                asked
     * @throws MetadataFetchException if no source found the book
     */
    public LookupResult lookUp(String isbn) throws InvalidISBNException, MetadataFetchException {
        return lookUp(isbn, false);
    }

    /**
     * Looks a book up, optionally skipping the cache - the "Look up again"
     * button, for when a cached answer was incomplete or wrong.
     *
     * @param isbn        the ISBN as typed or scanned, 10 or 13 digits
     * @param bypassCache true to ask the sources even if a cached answer
     *                    exists
     * @return what was found and where from
     * @throws InvalidISBNException   if the ISBN is not valid
     * @throws MetadataFetchException if no source found the book
     */
    public LookupResult lookUp(String isbn, boolean bypassCache)
            throws InvalidISBNException, MetadataFetchException {
        long started = System.nanoTime();
        // One key per book, whichever form was typed (L12).
        String isbn13 = IsbnValidator.toIsbn13(isbn);

        if (!bypassCache && cache != null) {
            LookupResult cached = cache.get(isbn13);
            if (cached != null) {
                return new LookupResult(isbn13, cached.getMetadata(), cached.getSources(),
                        cached.getUnavailable(), true, elapsedMillis(started));
            }
        }

        BookMetadata merged = null;
        List<String> used = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        for (int i = 0; i < sources.size(); i++) {
            if (merged != null && !hasGaps(merged)) {
                break; // complete: no need to ask anyone else
            }
            MetadataProvider source = sources.get(i);
            try {
                BookMetadata found = source.fetchByIsbn(isbn13);
                if (merged == null) {
                    merged = found;
                    used.add(source.getProviderName());
                } else if (fillGaps(merged, found)) {
                    used.add(source.getProviderName());
                }
            } catch (MetadataFetchException e) {
                unavailable.add(source.getProviderName() + ": " + e.getReason());
            }
        }

        if (merged == null) {
            throw new MetadataFetchException(ALL_SOURCES, isbn13, String.join("; ", unavailable));
        }

        LookupResult result = new LookupResult(isbn13, merged, used, unavailable, false, elapsedMillis(started));
        if (cache != null) {
            try {
                cache.put(result);
            } catch (IOException e) {
                // Caching is a convenience. A full disk must not turn a
                // successful lookup into a failure.
            }
        }
        return result;
    }

    /**
     * Whether a result is missing a field the Add form shows. Subtitle and
     * cover do not count: their absence alone is not worth another network
     * call.
     *
     * @param m the details so far
     * @return true if another source should be asked
     */
    static boolean hasGaps(BookMetadata m) {
        return m.getAuthors().isEmpty() || m.getPublisher() == null || m.getYear() == 0
                || m.getPages() == 0 || m.getLanguage() == null;
    }

    /**
     * Copies into {@code base} whatever it lacks and {@code extra} has.
     * Never overwrites a value {@code base} already holds: the first source
     * to find the book is trusted for everything it supplied.
     *
     * @param base  the details so far, filled in place
     * @param extra details from a later source
     * @return true if at least one field was filled
     */
    static boolean fillGaps(BookMetadata base, BookMetadata extra) {
        boolean filled = false;
        if (base.getAuthors().isEmpty() && !extra.getAuthors().isEmpty()) {
            for (int i = 0; i < extra.getAuthors().size(); i++) {
                base.addAuthor(extra.getAuthors().get(i));
            }
            filled = true;
        }
        if (base.getSubtitle() == null && extra.getSubtitle() != null) {
            base.setSubtitle(extra.getSubtitle());
            filled = true;
        }
        if (base.getPublisher() == null && extra.getPublisher() != null) {
            base.setPublisher(extra.getPublisher());
            filled = true;
        }
        if (base.getYear() == 0 && extra.getYear() != 0) {
            base.setYear(extra.getYear());
            filled = true;
        }
        if (base.getPages() == 0 && extra.getPages() != 0) {
            base.setPages(extra.getPages());
            filled = true;
        }
        if (base.getLanguage() == null && extra.getLanguage() != null) {
            base.setLanguage(extra.getLanguage());
            filled = true;
        }
        if (base.getCoverUrl() == null && extra.getCoverUrl() != null) {
            base.setCoverUrl(extra.getCoverUrl());
            filled = true;
        }
        return filled;
    }

    /**
     * @param startedNanos a {@link System#nanoTime()} reading
     * @return milliseconds since then
     */
    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
