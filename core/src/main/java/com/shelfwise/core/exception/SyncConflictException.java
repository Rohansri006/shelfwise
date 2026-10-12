package com.shelfwise.core.exception;

import java.time.Instant;

/**
 * Thrown when the data file changed on disk after ShelfWise loaded it.
 * <p>
 * The data folder is chosen by the user, which means it can be a synced
 * folder such as Dropbox or Syncthing. If another machine writes to the file
 * while this copy of the app has it open, saving would silently destroy that
 * other machine's work.
 * <p>
 * So before every save the file's last-modified time is compared with the
 * value recorded at load. If they differ, nothing is written and this
 * exception is thrown carrying both timestamps, letting the user choose:
 * keep this session's version, or discard it and reload from disk. There is
 * deliberately no automatic merge - guessing would risk losing data, and a
 * wrong guess is worse than a question.
 */
public class SyncConflictException extends ShelfWiseException {

    /** When this session loaded the file. */
    private final Instant loadedAt;

    /** When the file on disk was actually last modified. */
    private final Instant fileModifiedAt;

    /**
     * @param loadedAt       when this session read the file
     * @param fileModifiedAt the file's current last-modified time
     */
    public SyncConflictException(Instant loadedAt, Instant fileModifiedAt) {
        super("The data file changed on disk after it was loaded"
                + " (loaded at " + loadedAt + ", file modified at " + fileModifiedAt
                + "). Nothing was saved.");
        this.loadedAt = loadedAt;
        this.fileModifiedAt = fileModifiedAt;
    }

    /** @return when this session read the file */
    public Instant getLoadedAt() {
        return loadedAt;
    }

    /** @return the file's last-modified time at the point of the clash */
    public Instant getFileModifiedAt() {
        return fileModifiedAt;
    }
}
