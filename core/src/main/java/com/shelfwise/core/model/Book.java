package com.shelfwise.core.model;

import com.shelfwise.core.exception.InvalidISBNException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A book in the collection: the only kind of item in version 1.
 * <p>
 * <strong>Bibliographic facts only.</strong> Everything here describes the
 * work itself - title, ISBN, who wrote it, how long it is. Everything about
 * the physical objects on the shelf lives in {@link Copy}. The test for which
 * side a field belongs on is simple: would it differ between two copies of
 * the same title? Page count would not, so it is here. Condition would, so it
 * is not.
 * <p>
 * <strong>Every book owns at least one copy.</strong> Owning a book means
 * having a copy of it, so every constructor creates one. That invariant is
 * what lets the browse screen count copies without null checks, and it is
 * re-checked by {@link #validate()}.
 * <p>
 * <strong>The constructors are overloaded and chained.</strong> Each one adds
 * a little more information and delegates to the next via {@code this(...)},
 * so the rules for building a valid book exist in exactly one place.
 */
public class Book extends CollectionItem {

    /** Optional subtitle. */
    private String subtitle;

    /** Normalised ISBN, or null. Optional because books predating barcodes
     *  exist, which is why the internal id and not the ISBN is the identity. */
    private String isbn;

    /** Everyone credited on the book, in display order. */
    private final List<Contributor> contributors;

    /** Publisher name, optional. */
    private String publisher;

    /** Year of publication. 0 means unknown. */
    private int publicationYear;

    /** Language, optional free text. */
    private String language;

    /** Page count. 0 means unknown. */
    private int pages;

    /** Id of the series this belongs to, or null. */
    private String seriesId;

    /** Position within that series. 0 means not in a series. */
    private int seriesPosition;

    /** How far the user has got. Never null. */
    private ReadingStatus readingStatus;

    /** The physical copies owned. Always at least one. */
    private final List<Copy> copies;

    /** Every recorded read-through of this book. May be empty. */
    private final List<ReadingSession> readingSessions;

    /**
     * Creates a book with only a title, and one default copy.
     * <p>
     * Note the single argument is the <em>title</em>, not an ISBN. A title is
     * required and an ISBN is not, so this is the genuine minimum. For adding
     * a scanned book, use one of the overloads that takes the ISBN first.
     *
     * @param title the book's title; must not be blank
     * @throws IllegalArgumentException if the title is blank
     */
    public Book(String title) {
        super(title);
        this.contributors = new ArrayList<>();
        this.copies = new ArrayList<>();
        this.readingSessions = new ArrayList<>();
        this.readingStatus = ReadingStatus.UNREAD;
        this.publicationYear = 0;
        this.pages = 0;
        this.seriesPosition = 0;
        // The "every book has a copy" invariant, established at birth.
        this.copies.add(new Copy());
    }

    /**
     * Creates a book with an ISBN and a title.
     *
     * @param isbn  the ISBN; validated, and may be null to omit it
     * @param title the book's title; must not be blank
     * @throws IllegalArgumentException if the title is blank
     * @throws InvalidISBNException     if the ISBN is present but invalid
     */
    public Book(String isbn, String title) throws InvalidISBNException {
        // Chains to the simplest constructor, so the invariants it
        // establishes are never duplicated or forgotten here.
        this(title);
        setIsbn(isbn);
    }

    /**
     * Creates a book with an ISBN, a title and its author - the shape a
     * metadata lookup usually returns.
     *
     * @param isbn   the ISBN; validated, and may be null to omit it
     * @param title  the book's title; must not be blank
     * @param author the author; may be null to omit
     * @throws IllegalArgumentException if the title is blank
     * @throws InvalidISBNException     if the ISBN is present but invalid
     */
    public Book(String isbn, String title, Contributor author) throws InvalidISBNException {
        this(isbn, title);
        if (author != null) {
            addContributor(author);
        }
    }

    /**
     * Promotes a wishlist entry into an owned book.
     * <p>
     * Carries across what still applies - the title, the ISBN, the
     * remembered author - and drops what does not: an expected price is
     * replaced by whatever was actually paid, recorded on the copy, and the
     * intended shop is not worth keeping once the book is in hand.
     * <p>
     * The ISBN is validated here even though the wishlist accepted it
     * unchecked, because a real book's ISBN has to be trustworthy.
     *
     * @param entry the wishlist entry being bought; must not be null
     * @throws IllegalArgumentException if the entry is null
     * @throws InvalidISBNException     if the entry holds an unusable ISBN
     */
    public Book(WishlistEntry entry) throws InvalidISBNException {
        this(entry == null ? null : entry.getIsbn(),
                entry == null ? "" : entry.getTitle());

        if (entry == null) {
            throw new IllegalArgumentException("Wishlist entry must not be null.");
        }
        if (entry.getAuthor() != null && !entry.getAuthor().trim().isEmpty()) {
            addContributor(new Contributor(entry.getAuthor()));
        }
    }

    /**
     * Recreates a book that already exists, keeping its identity and with no
     * copies added automatically. Used by the storage layer, which adds the
     * copies it loaded from the database.
     *
     * @param id        the existing identity
     * @param title     the book's title; must not be blank
     * @param dateAdded when it was originally added
     * @throws IllegalArgumentException if the title is blank
     */
    public Book(String id, String title, LocalDate dateAdded) {
        super(id, title, dateAdded);
        this.contributors = new ArrayList<>();
        this.copies = new ArrayList<>();
        this.readingSessions = new ArrayList<>();
        this.readingStatus = ReadingStatus.UNREAD;
        this.publicationYear = 0;
        this.pages = 0;
        this.seriesPosition = 0;
    }

    // ---------------------------------------------------------------
    // Abstract methods from CollectionItem
    // ---------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>
     * Prefers the ISBN, because that is what the outside world - barcodes,
     * metadata providers, other people's catalogues - recognises. Falls back
     * to the internal id for books without one.
     */
    @Override
    public String getIdentifier() {
        if (isbn != null && !isbn.isEmpty()) {
            return isbn;
        }
        return getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Checks the whole-object rules the individual setters cannot see.
     */
    @Override
    public void validate() {
        if (getTitle() == null || getTitle().trim().isEmpty()) {
            throw new IllegalArgumentException("A book must have a title.");
        }
        if (copies.isEmpty()) {
            throw new IllegalArgumentException(
                    "A book must have at least one copy; \"" + getTitle() + "\" has none.");
        }
        if (seriesPosition > 0 && seriesId == null) {
            throw new IllegalArgumentException(
                    "\"" + getTitle() + "\" has a series position but no series.");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Built to read naturally even when most fields are empty, which is the
     * normal state of a freshly scanned book.
     */
    @Override
    public String getDisplaySummary() {
        StringBuilder text = new StringBuilder(getTitle());

        Contributor author = getPrimaryAuthor();
        if (author != null) {
            text.append(" by ").append(author.getName());
        }
        if (publicationYear > 0) {
            text.append(" (").append(publicationYear).append(')');
        }
        if (copies.size() > 1) {
            text.append(" [").append(copies.size()).append(" copies]");
        }
        return text.toString();
    }

    // ---------------------------------------------------------------
    // Bibliographic fields
    // ---------------------------------------------------------------

    /** @return the subtitle, or null */
    public String getSubtitle() {
        return subtitle;
    }

    /** @param subtitle the subtitle; null is acceptable */
    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    /** @return the normalised ISBN, or null if the book has none */
    public String getIsbn() {
        return isbn;
    }

    /**
     * Validates an ISBN and stores it in its canonical 13-digit form.
     * <p>
     * Hyphens and spaces are stripped, and an ISBN-10 is converted to its
     * ISBN-13 twin, so "0-14-303105-8", "978-0-14-303105-5" and
     * "9780143031055" all store as "9780143031055". One book then has exactly
     * one stored ISBN however it was typed or scanned, which is what makes
     * duplicate detection reliable. The conversion happens here, in the
     * setter, so every route in (constructor, edit form, import, database
     * load) gets it without having to remember.
     *
     * @param isbn the ISBN as entered, 10 or 13 digits; null or blank clears it
     * @throws InvalidISBNException if present but not a valid ISBN
     */
    public void setIsbn(String isbn) throws InvalidISBNException {
        if (isbn == null || isbn.trim().isEmpty()) {
            this.isbn = null;
            return;
        }
        this.isbn = IsbnValidator.toIsbn13(isbn);
    }

    /** @return true if this book has an ISBN recorded */
    public boolean hasIsbn() {
        return isbn != null && !isbn.isEmpty();
    }

    /** @return the publisher, or null */
    public String getPublisher() {
        return publisher;
    }

    /** @param publisher the publisher; null is acceptable */
    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    /** @return the year of publication, or 0 if unknown */
    public int getPublicationYear() {
        return publicationYear;
    }

    /**
     * @param publicationYear the year; 0 means unknown
     * @throws IllegalArgumentException if outside a plausible range. The
     *         lower bound is 1450, around the invention of the printing
     *         press; the upper allows next year, since books are often
     *         dated ahead of their actual release
     */
    public void setPublicationYear(int publicationYear) {
        if (publicationYear != 0
                && (publicationYear < 1450 || publicationYear > LocalDate.now().getYear() + 1)) {
            throw new IllegalArgumentException(
                    "Publication year must be 0 or between 1450 and "
                            + (LocalDate.now().getYear() + 1)
                            + ", but was " + publicationYear + ".");
        }
        this.publicationYear = publicationYear;
    }

    /** @return the language, or null */
    public String getLanguage() {
        return language;
    }

    /** @param language the language; null is acceptable */
    public void setLanguage(String language) {
        this.language = language;
    }

    /** @return the page count, or 0 if unknown */
    public int getPages() {
        return pages;
    }

    /**
     * @param pages the page count; 0 means unknown
     * @throws IllegalArgumentException if negative
     */
    public void setPages(int pages) {
        if (pages < 0) {
            throw new IllegalArgumentException(
                    "Page count cannot be negative, but was " + pages + ".");
        }
        this.pages = pages;
    }

    /** @return id of the series, or null */
    public String getSeriesId() {
        return seriesId;
    }

    /** @param seriesId the series id, or null to remove from its series */
    public void setSeriesId(String seriesId) {
        this.seriesId = seriesId;
        if (seriesId == null) {
            // A position is meaningless without a series, so clear it too
            // rather than leaving the object in a state validate() rejects.
            this.seriesPosition = 0;
        }
    }

    /** @return position in the series, or 0 if not in one */
    public int getSeriesPosition() {
        return seriesPosition;
    }

    /**
     * @param seriesPosition the position; 0 means not in a series
     * @throws IllegalArgumentException if negative
     */
    public void setSeriesPosition(int seriesPosition) {
        if (seriesPosition < 0) {
            throw new IllegalArgumentException(
                    "Series position cannot be negative, but was " + seriesPosition + ".");
        }
        this.seriesPosition = seriesPosition;
    }

    /** @return how far the user has got */
    public ReadingStatus getReadingStatus() {
        return readingStatus;
    }

    /**
     * @param readingStatus the status; must not be null
     * @throws IllegalArgumentException if null
     */
    public void setReadingStatus(ReadingStatus readingStatus) {
        if (readingStatus == null) {
            throw new IllegalArgumentException("Reading status must not be null.");
        }
        this.readingStatus = readingStatus;
    }

    // ---------------------------------------------------------------
    // Contributors
    // ---------------------------------------------------------------

    /** @return an unmodifiable view of everyone credited */
    public List<Contributor> getContributors() {
        return Collections.unmodifiableList(contributors);
    }

    /**
     * Adds a credit, ignoring exact duplicates.
     *
     * @param contributor the person and role; must not be null
     * @throws IllegalArgumentException if null
     */
    public void addContributor(Contributor contributor) {
        if (contributor == null) {
            throw new IllegalArgumentException("Contributor must not be null.");
        }
        // Relies on Contributor.equals comparing name and role.
        if (!contributors.contains(contributor)) {
            contributors.add(contributor);
        }
    }

    /**
     * @param contributor the credit to remove
     * @return true if it was present
     */
    public boolean removeContributor(Contributor contributor) {
        return contributors.remove(contributor);
    }

    /**
     * The first author credited, which is what list views show.
     *
     * @return the first contributor whose role is AUTHOR, or null if nobody
     *         is credited as an author
     */
    public Contributor getPrimaryAuthor() {
        for (int i = 0; i < contributors.size(); i++) {
            Contributor candidate = contributors.get(i);
            if (candidate.getRole() == ContributorRole.AUTHOR) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Copies
    // ---------------------------------------------------------------

    /** @return an unmodifiable view of the copies owned */
    public List<Copy> getCopies() {
        return Collections.unmodifiableList(copies);
    }

    /** @return how many copies are owned; always at least one in practice */
    public int getCopyCount() {
        return copies.size();
    }

    /**
     * Adds another copy, which is what happens when a duplicate ISBN is
     * resolved as "I own two of these".
     *
     * @param copy the copy; must not be null
     * @throws IllegalArgumentException if null
     */
    public void addCopy(Copy copy) {
        if (copy == null) {
            throw new IllegalArgumentException("Copy must not be null.");
        }
        copies.add(copy);
    }

    /**
     * Removes a copy, refusing to remove the last one.
     * <p>
     * A book with no copies is not a book you own, so getting rid of the only
     * copy means deleting the book - a different operation, handled by the
     * trash.
     *
     * @param copy the copy to remove
     * @return true if it was present and removed
     * @throws IllegalArgumentException if it is the only remaining copy
     */
    public boolean removeCopy(Copy copy) {
        if (copies.size() == 1 && copies.contains(copy)) {
            throw new IllegalArgumentException(
                    "Cannot remove the only copy of \"" + getTitle()
                            + "\". Delete the book instead.");
        }
        return copies.remove(copy);
    }

    /**
     * Total spent on this book across every copy.
     *
     * @return the sum of recorded prices; 0 if none were recorded
     */
    public double getTotalPricePaid() {
        double total = 0;
        for (int i = 0; i < copies.size(); i++) {
            total += copies.get(i).getPricePaid();
        }
        return total;
    }

    // ---------------------------------------------------------------
    // Reading history
    // ---------------------------------------------------------------

    /** @return an unmodifiable view of the recorded reading sessions */
    public List<ReadingSession> getReadingSessions() {
        return Collections.unmodifiableList(readingSessions);
    }

    /**
     * Records a reading session and updates the reading status to match.
     * <p>
     * The status is kept as a stored field rather than derived on every read,
     * but it still has to stay consistent with the history - so adding an
     * open session marks the book as being read, and adding a finished one
     * marks it finished. The user can always override afterwards.
     *
     * @param session the session; must not be null
     * @throws IllegalArgumentException if null
     */
    public void addReadingSession(ReadingSession session) {
        if (session == null) {
            throw new IllegalArgumentException("Reading session must not be null.");
        }
        readingSessions.add(session);

        if (session.isFinished()) {
            this.readingStatus = ReadingStatus.FINISHED;
        } else if (this.readingStatus == ReadingStatus.UNREAD) {
            this.readingStatus = ReadingStatus.READING;
        }
    }

    /**
     * Closes an open reading session and marks the book finished: what the
     * "Finished reading" button does.
     * <p>
     * Setting the end date on the session directly would leave the status
     * at READING, because a session knows nothing about its book. Going
     * through the book keeps the two in step, the same way
     * {@link #addReadingSession} does when a session is added already
     * finished.
     *
     * @param session one of this book's sessions
     * @param endDate the day it was finished; not before the session started
     * @throws IllegalArgumentException if the session is not this book's,
     *                                  the date is null, or it is before the
     *                                  start date
     */
    public void finishReadingSession(ReadingSession session, LocalDate endDate) {
        if (session == null || !readingSessions.contains(session)) {
            throw new IllegalArgumentException(
                    "That reading session does not belong to \"" + getTitle() + "\".");
        }
        if (endDate == null) {
            throw new IllegalArgumentException("A finished session needs an end date.");
        }
        // The session validates the date first; if it refuses, the status
        // below is never touched.
        session.setEndDate(endDate);
        this.readingStatus = ReadingStatus.FINISHED;
    }

    /**
     * @param session the session to remove
     * @return true if it was present
     */
    public boolean removeReadingSession(ReadingSession session) {
        return readingSessions.remove(session);
    }

    /**
     * How many times this book has been read right through.
     *
     * @return the number of finished sessions
     */
    public int getTimesRead() {
        int count = 0;
        for (int i = 0; i < readingSessions.size(); i++) {
            if (readingSessions.get(i).isFinished()) {
                count++;
            }
        }
        return count;
    }
}
