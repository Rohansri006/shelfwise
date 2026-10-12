package com.shelfwise.core.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One attempt at reading a book, from the day it was started to the day it
 * was finished or set aside.
 * <p>
 * A list of these rather than a single "date read" because people reread
 * books, and abandon them and come back years later. Collapsing that to one
 * date would throw away the more interesting half of the history.
 * <p>
 * Progress can be recorded as pages, as a percentage, as both, or as neither.
 * All are optional because the honest answer is often "I read it, I did not
 * track it". Following the model-wide rule, 0 means not recorded.
 */
public class ReadingSession {

    /** Permanent internal identity. */
    private final String id;

    /** The day reading began. Required - a session with no start is not an
     *  event that happened. */
    private LocalDate startDate;

    /** The day it was finished, or null while still in progress. */
    private LocalDate endDate;

    /** Pages read. 0 means not recorded. */
    private int pagesRead;

    /** Percentage read, 0 to 100. 0 means not recorded. */
    private int percentComplete;

    /**
     * Starts a session today.
     */
    public ReadingSession() {
        this(LocalDate.now());
    }

    /**
     * Starts a session on a given day.
     *
     * @param startDate the day reading began; must not be null
     * @throws IllegalArgumentException if null
     */
    public ReadingSession(LocalDate startDate) {
        this(UUID.randomUUID().toString(), startDate);
    }

    /**
     * Recreates a session that already exists, keeping its identity. Used by
     * the storage layer when loading.
     *
     * @param id        the existing identity
     * @param startDate the day reading began; must not be null
     * @throws IllegalArgumentException if null
     */
    public ReadingSession(String id, LocalDate startDate) {
        this.id = id;
        this.pagesRead = 0;
        this.percentComplete = 0;
        setStartDate(startDate);
    }

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return the day reading began */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * @param startDate the day reading began; must not be null
     * @throws IllegalArgumentException if null, or later than an existing
     *                                  end date
     */
    public void setStartDate(LocalDate startDate) {
        if (startDate == null) {
            throw new IllegalArgumentException("A reading session must have a start date.");
        }
        if (endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "Start date " + startDate + " is after the end date " + endDate + ".");
        }
        this.startDate = startDate;
    }

    /** @return the day it was finished, or null if still in progress */
    public LocalDate getEndDate() {
        return endDate;
    }

    /**
     * @param endDate the day it was finished; null means still reading
     * @throws IllegalArgumentException if before the start date
     */
    public void setEndDate(LocalDate endDate) {
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "End date " + endDate + " is before the start date " + startDate + ".");
        }
        this.endDate = endDate;
    }

    /** @return true once an end date has been recorded */
    public boolean isFinished() {
        return endDate != null;
    }

    /** @return pages read, or 0 if not recorded */
    public int getPagesRead() {
        return pagesRead;
    }

    /**
     * @param pagesRead pages read; 0 means not recorded
     * @throws IllegalArgumentException if negative
     */
    public void setPagesRead(int pagesRead) {
        if (pagesRead < 0) {
            throw new IllegalArgumentException(
                    "Pages read cannot be negative, but was " + pagesRead + ".");
        }
        this.pagesRead = pagesRead;
    }

    /** @return percentage read, or 0 if not recorded */
    public int getPercentComplete() {
        return percentComplete;
    }

    /**
     * @param percentComplete percentage read; 0 means not recorded
     * @throws IllegalArgumentException if outside 0 to 100
     */
    public void setPercentComplete(int percentComplete) {
        if (percentComplete < 0 || percentComplete > 100) {
            throw new IllegalArgumentException(
                    "Percent complete must be between 0 and 100, but was "
                            + percentComplete + ".");
        }
        this.percentComplete = percentComplete;
    }

    /**
     * How many days the session lasted, counting both end days.
     *
     * @return the number of days, or 0 while still in progress
     */
    public int getDurationInDays() {
        if (endDate == null) {
            return 0;
        }
        return (int) (endDate.toEpochDay() - startDate.toEpochDay()) + 1;
    }

    /**
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReadingSession)) {
            return false;
        }
        return id.equals(((ReadingSession) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return the date range, or an open-ended range if still reading */
    @Override
    public String toString() {
        if (endDate == null) {
            return startDate + " - reading";
        }
        return startDate + " to " + endDate;
    }
}
