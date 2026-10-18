package com.shelfwise.core.service;

import com.shelfwise.core.exception.SyncConflictException;

import java.io.IOException;

/**
 * Somewhere a whole collection can be saved and loaded.
 * <p>
 * The service layer talks only to this interface, never to SQLite. On the
 * desktop the implementation is {@code SqliteStorage}; the future Android
 * app needs a different one, because JDBC drivers do not run on Android.
 * Because nothing above this line knows which is in use, the swap touches
 * no other code - this interface is the seam that makes {@code core}
 * reusable.
 */
public interface StorageProvider {

    /**
     * Writes the whole collection.
     *
     * @param data everything to save
     * @throws IOException           if the write fails; nothing is half-saved
     * @throws SyncConflictException if the stored data changed since it was
     *                               loaded, in which case nothing is written
     */
    void save(CollectionData data) throws IOException, SyncConflictException;

    /**
     * Reads the whole collection.
     *
     * @return everything stored; empty lists if nothing has been saved
     * @throws IOException if the stored data cannot be read
     */
    CollectionData load() throws IOException;

    /**
     * Whether anything has been saved yet - how the app tells a first run
     * from a returning one.
     *
     * @return true if stored data exists
     */
    boolean dataExists();
}
