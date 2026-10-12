package com.shelfwise.core.model;

/**
 * Where the user has got to with a book.
 * <p>
 * Stored as a field on {@link Book} and updated when a reading session is
 * added or closed, rather than being worked out from the session history on
 * every read. Deriving it would mean a rule such as "finished if the latest
 * closed session reached the last page", which is both slower to query and
 * harder to defend than a value the user can simply set.
 * <p>
 * {@link #ABANDONED} exists because "started and gave up" is genuinely
 * different from "not started", and collapsing the two loses information the
 * user cared enough to record.
 */
public enum ReadingStatus {

    UNREAD("Unread"),
    READING("Reading"),
    FINISHED("Finished"),
    ABANDONED("Abandoned");

    /** How this status is shown to the user. */
    private final String label;

    /**
     * @param label the display name for this status
     */
    ReadingStatus(String label) {
        this.label = label;
    }

    /** @return the display name, e.g. "Finished" */
    public String getLabel() {
        return label;
    }

    /**
     * Whether the user has engaged with this book at all.
     * <p>
     * Used by the statistics screen, where "books touched" is a more honest
     * figure than "books finished".
     *
     * @return true for anything other than {@link #UNREAD}
     */
    public boolean hasBeenStarted() {
        return this != UNREAD;
    }

    /** @return the display name */
    @Override
    public String toString() {
        return label;
    }
}
