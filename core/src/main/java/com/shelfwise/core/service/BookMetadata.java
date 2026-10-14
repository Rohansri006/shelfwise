package com.shelfwise.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an online catalogue knows about a book, as returned by a
 * {@link MetadataProvider}.
 * <p>
 * A plain carrier, deliberately separate from {@link
 * com.shelfwise.core.model.Book}. A lookup result is a <em>suggestion</em>
 * shown on the Add Book form for the user to accept or edit; it is not a book
 * the user owns. Keeping it separate also means a half-filled API response
 * never has to satisfy every rule a real {@code Book} enforces - authors here
 * are plain names, not {@code Contributor} objects with roles.
 * <p>
 * Optional numbers follow the project convention: 0 means "not known".
 */
public class BookMetadata {

    /** The book's title. Required: a lookup that found no title found nothing. */
    private String title;

    /** Optional subtitle. */
    private String subtitle;

    /** Author names in the order the catalogue lists them. */
    private final List<String> authors;

    /** Optional publisher. */
    private String publisher;

    /** Publication year. 0 means unknown. */
    private int year;

    /** Page count. 0 means unknown. */
    private int pages;

    /** Optional language, as the catalogue gives it. */
    private String language;

    /** Optional address of a cover image. */
    private String coverUrl;

    /**
     * Creates a result holding only a title.
     *
     * @param title the book's title; must not be blank
     * @throws IllegalArgumentException if the title is blank
     */
    public BookMetadata(String title) {
        this.authors = new ArrayList<>();
        setTitle(title);
    }

    /** @return the title */
    public String getTitle() {
        return title;
    }

    /**
     * @param title the title; must not be null or blank
     * @throws IllegalArgumentException if null or blank
     */
    public void setTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Metadata title must not be blank.");
        }
        this.title = title.trim();
    }

    /** @return the subtitle, or null */
    public String getSubtitle() {
        return subtitle;
    }

    /** @param subtitle the subtitle; null is acceptable */
    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    /** @return an unmodifiable view of the author names */
    public List<String> getAuthors() {
        return Collections.unmodifiableList(authors);
    }

    /**
     * Adds an author name. Blank names are ignored rather than rejected,
     * because catalogues do return empty entries and a lookup should not fail
     * over one.
     *
     * @param name the author's name
     */
    public void addAuthor(String name) {
        if (name != null && !name.trim().isEmpty()) {
            authors.add(name.trim());
        }
    }

    /** @return the publisher, or null */
    public String getPublisher() {
        return publisher;
    }

    /** @param publisher the publisher; null is acceptable */
    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    /** @return the publication year, or 0 if unknown */
    public int getYear() {
        return year;
    }

    /**
     * Records the year without the range check {@code Book} applies: the
     * catalogue's value is shown to the user, and {@code Book} validates it
     * if they accept it.
     *
     * @param year the year; 0 means unknown
     * @throws IllegalArgumentException if negative
     */
    public void setYear(int year) {
        if (year < 0) {
            throw new IllegalArgumentException("Year cannot be negative, but was " + year + ".");
        }
        this.year = year;
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
            throw new IllegalArgumentException("Pages cannot be negative, but was " + pages + ".");
        }
        this.pages = pages;
    }

    /** @return the language, or null */
    public String getLanguage() {
        return language;
    }

    /** @param language the language; null is acceptable */
    public void setLanguage(String language) {
        this.language = language;
    }

    /** @return the cover image address, or null */
    public String getCoverUrl() {
        return coverUrl;
    }

    /** @param coverUrl the cover image address; null is acceptable */
    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    /** @return title and first author, for logs and debugging */
    @Override
    public String toString() {
        if (authors.isEmpty()) {
            return title;
        }
        return title + " by " + authors.get(0);
    }
}
