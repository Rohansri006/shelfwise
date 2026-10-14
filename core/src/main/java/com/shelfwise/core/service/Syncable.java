package com.shelfwise.core.service;

import java.io.IOException;
import java.time.LocalDate;

/**
 * Something whose data can be kept in step with a copy on another device.
 * <p>
 * <strong>Defined only - nothing in version 1 implements it.</strong> It
 * marks where multi-device sync will attach in a later version: a sync
 * service would implement this alongside {@link StorageProvider}, and the
 * rest of the code would be unaffected. Declaring the seam now, with no
 * implementation, records the design without taking on the risk of
 * building sync before the deadline.
 */
public interface Syncable {

    /**
     * Sends local changes to the remote copy.
     *
     * @throws IOException if the remote copy cannot be reached or written
     */
    void push() throws IOException;

    /**
     * Fetches remote changes into the local copy.
     *
     * @throws IOException if the remote copy cannot be reached or read
     */
    void pull() throws IOException;

    /**
     * When the two copies were last brought in step.
     *
     * @return the date, or null if they never have been
     */
    LocalDate getLastSyncDate();
}
