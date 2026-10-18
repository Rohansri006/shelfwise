package com.shelfwise.core.model;

import com.shelfwise.core.exception.InvalidISBNException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A named list of books the user wants - "Birthday ideas", "Out of print,
 * keep looking" - holding its {@link WishlistEntry} items.
 * <p>
 * <strong>This is composition, not association.</strong> An entry is a note
 * inside one particular list and means nothing on its own, so the list owns
 * its entries outright, exactly as a {@link Book} owns its {@link Copy}
 * objects. The database says the same thing: an entry's
 * {@code wishlist_id} is {@code NOT NULL} and {@code ON DELETE CASCADE}, so
 * deleting a list deletes its entries with it and leaves no orphans.
 * <p>
 * The schema's three delete rules follow from what each link protects, and
 * together they show how UML relationships become SQL:
 * <ul>
 *   <li>{@code CASCADE} - entry to wishlist: an owned part goes with its
 *       whole.</li>
 *   <li>{@code SET NULL} - book to {@code Series}: an independent object
 *       survives losing its group.</li>
 *   <li>{@code RESTRICT} - copy to {@link Location}, item to
 *       {@link Category}: deleting would strand books the user owns, so the
 *       delete is refused.</li>
 * </ul>
 * <p>
 * List names are unique across the collection. Only something that sees
 * every list can enforce that, so it is left to the database's
 * {@code UNIQUE} constraint and the service layer.
 */
public class Wishlist {

    /** Permanent internal identity. */
    private final String id;

    /** Display name. Required. */
    private String name;

    /** The entries this list owns, in the order they were added. */
    private final List<WishlistEntry> entries;

    /**
     * Creates an empty list with a fresh identity.
     *
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Wishlist(String name) {
        this(UUID.randomUUID().toString(), name);
    }

    /**
     * Recreates a list that already exists, keeping its identity. Used by the
     * storage layer when loading.
     * <p>
     * Starts empty: the DAO adds the entries it loaded from the database.
     *
     * @param id   the existing identity
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Wishlist(String id, String name) {
        this.id = id;
        this.entries = new ArrayList<>();
        setName(name);
    }

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return the display name */
    public String getName() {
        return name;
    }

    /**
     * @param name the display name; must not be null or blank
     * @throws IllegalArgumentException if null or blank
     */
    public void setName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Wishlist name must not be blank.");
        }
        this.name = name.trim();
    }

    /** @return an unmodifiable view of the entries, in the order added */
    public List<WishlistEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** @return how many entries the list holds */
    public int getEntryCount() {
        return entries.size();
    }

    /** @return true if the list holds no entries */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Adds an entry to the end of the list.
     * <p>
     * Two entries with the same title are allowed - wanting both the
     * hardback and a cheap paperback of one title is legitimate. Adding the
     * same entry twice is not, because it would then appear twice on screen
     * and be saved as two rows with one id.
     *
     * @param entry the entry; must not be null
     * @throws IllegalArgumentException if null or already in this list
     */
    public void addEntry(WishlistEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Wishlist entry must not be null.");
        }
        // contains() uses WishlistEntry.equals, which compares ids
        if (entries.contains(entry)) {
            throw new IllegalArgumentException(
                    "\"" + entry.getTitle() + "\" is already on \"" + name + "\".");
        }
        entries.add(entry);
    }

    /**
     * Removes an entry.
     *
     * @param entry the entry to remove
     * @return true if it was in this list and has been removed
     */
    public boolean removeEntry(WishlistEntry entry) {
        return entries.remove(entry);
    }

    /**
     * Finds an entry by its id.
     *
     * @param entryId the id to look for
     * @return the matching entry, or null if there is none
     */
    public WishlistEntry findEntryById(String entryId) {
        if (entryId == null) {
            return null;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId().equals(entryId)) {
                return entries.get(i);
            }
        }
        return null;
    }

    /**
     * Turns a wanted book into an owned one: what the "Mark as Bought"
     * button does.
     * <p>
     * The order of the two steps matters. The {@link Book} is built
     * <em>first</em>, because that is where the ISBN is finally validated -
     * the wishlist accepted it unchecked. If it is invalid the exception
     * escapes before anything has changed, so the entry stays on the list
     * for the user to correct. Removing first would lose the entry on a typo.
     * <p>
     * Adding the returned book to the collection is the service layer's
     * job, not this class's. Screens should call
     * {@code CollectionManager.markAsBought}, which also refuses a duplicate
     * <em>before</em> this method removes the entry.
     *
     * @param entry the entry that was bought; must be in this list
     * @return a new book carrying across the entry's title, ISBN and author
     * @throws IllegalArgumentException if the entry is not in this list
     * @throws InvalidISBNException     if the entry's ISBN is unusable; the
     *                                  list is then left unchanged
     */
    public Book markAsBought(WishlistEntry entry) throws InvalidISBNException {
        if (entry == null || !entries.contains(entry)) {
            String title = entry == null ? "null" : "\"" + entry.getTitle() + "\"";
            throw new IllegalArgumentException(
                    title + " is not on the wishlist \"" + name + "\".");
        }
        Book book = new Book(entry);
        entries.remove(entry);
        return book;
    }

    /**
     * Identity is the id alone, so renaming a list keeps it the same list.
     *
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Wishlist)) {
            return false;
        }
        return id.equals(((Wishlist) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return the name, which is what the list selector shows */
    @Override
    public String toString() {
        return name;
    }
}
