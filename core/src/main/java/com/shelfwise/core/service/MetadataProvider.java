package com.shelfwise.core.service;

import com.shelfwise.core.exception.MetadataFetchException;

/**
 * An online catalogue that can look a book up by its ISBN.
 * <p>
 * The point of the interface is that the code asking for metadata never
 * names a concrete catalogue. It holds a {@code List<MetadataProvider>},
 * tries each in turn, and moves to the next when one throws. Which
 * {@code fetchByIsbn} body runs is decided at runtime by the object in the
 * list - Open Library first, Google Books as the fallback - so adding a third
 * catalogue means writing one class and adding it to the list, with no
 * change to the caller.
 */
public interface MetadataProvider {

    /**
     * The catalogue's name, used in error messages and in
     * {@link MetadataFetchException#getProviderName()}.
     *
     * @return e.g. "Open Library"
     */
    String getProviderName();

    /**
     * Looks a book up.
     * <p>
     * Blocks while the network request runs, so it must be called from a
     * background thread, never the JavaFX thread.
     *
     * @param isbn the ISBN, already validated
     * @return what the catalogue knows; never null
     * @throws MetadataFetchException if the catalogue cannot be reached, does
     *                                not know the ISBN, or returns something
     *                                unreadable
     */
    BookMetadata fetchByIsbn(String isbn) throws MetadataFetchException;
}
