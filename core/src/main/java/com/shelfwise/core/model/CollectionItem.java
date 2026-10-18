package com.shelfwise.core.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anything that can be in the collection. Abstract: there is no such thing as
 * a bare "item" - only books, and later perhaps comics or records.
 * <p>
 * <strong>What belongs here and what does not.</strong> This class holds only
 * what would be true of <em>any</em> collectable thing: it has an identity, a
 * title, a date it was added, somewhere to file it, a rating, notes, and a
 * trash flag. Anything specific to books - ISBN, pages, publisher - lives on
 * {@link Book}. Getting that line right is the whole point of the class: if
 * ISBN were here, a future {@code VinylRecord} subclass would inherit a field
 * that makes no sense for it.
 * <p>
 * <strong>Optional numbers use 0, not null.</strong> {@code rating} is 0 when
 * unrated. The same convention runs through the whole model, which avoids
 * null checks and {@code Optional} entirely at the cost of one rule to
 * remember.
 * <p>
 * <strong>Collections are returned as unmodifiable copies.</strong> A getter
 * that hands out the real {@code List} lets any caller modify an object's
 * insides without going through its validation - which defeats the point of
 * making the fields private.
 */
public abstract class CollectionItem {

    /** Permanent internal identity. Never changes, never reused. */
    private final String id;

    /** When this item was added to the collection. */
    private final LocalDate dateAdded;

    /** Required. Validated non-blank. */
    private String title;

    /** Free text, may be empty. */
    private String notes;

    /** 0 means unrated; otherwise 1 to 5. */
    private int rating;

    /** Ids of the categories this item is filed under. May be empty. */
    private final List<String> categoryIds;

    /**
     * User-defined extra fields, for anything the fixed model does not cover.
     * LinkedHashMap so the user's own ordering is preserved rather than
     * shuffled, which a plain HashMap would do.
     */
    private final Map<String, String> customFields;

    /** True when the item is in the trash. Soft delete: nothing is lost yet. */
    private boolean deleted;

    /**
     * Creates a new item with a freshly generated identity.
     *
     * @param title the item's title; must not be blank
     * @throws IllegalArgumentException if the title is blank
     */
    protected CollectionItem(String title) {
        // randomUUID gives an identifier unique without a central authority,
        // which matters because items are created offline and may later be
        // merged across devices or re-imported from CSV.
        this(UUID.randomUUID().toString(), title, LocalDate.now());
    }

    /**
     * Recreates an item that already exists, keeping its original identity.
     * Used by the storage layer when loading from the database; application
     * code should use the other constructor.
     *
     * @param id        the existing identity
     * @param title     the item's title; must not be blank
     * @param dateAdded when it was originally added
     * @throws IllegalArgumentException if the title is blank
     */
    protected CollectionItem(String id, String title, LocalDate dateAdded) {
        this.id = id;
        this.dateAdded = dateAdded;
        this.categoryIds = new ArrayList<>();
        this.customFields = new LinkedHashMap<>();
        this.rating = 0;
        this.deleted = false;
        // Through the setter, not the field, so one validation rule serves
        // both construction and later edits.
        setTitle(title);
    }

    // ---------------------------------------------------------------
    // Abstract behaviour: what every subclass must decide for itself
    // ---------------------------------------------------------------

    /**
     * How this item is identified to the outside world, as opposed to
     * internally. A book uses its ISBN when it has one and falls back to the
     * internal id when it does not.
     *
     * @return a non-null identifier suitable for display or export
     */
    public abstract String getIdentifier();

    /**
     * Checks that the item is complete and internally consistent.
     * <p>
     * Setters validate one field at a time; this validates the whole object,
     * catching problems no single setter can see - a book with no copies, for
     * instance.
     *
     * @throws IllegalArgumentException if the item is not valid
     */
    public abstract void validate();

    /**
     * A single line describing this item, for lists and tables.
     *
     * @return a human-readable one-line summary
     */
    public abstract String getDisplaySummary();

    // ---------------------------------------------------------------
    // Encapsulated state
    // ---------------------------------------------------------------

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return the date this item was added */
    public LocalDate getDateAdded() {
        return dateAdded;
    }

    /** @return the title */
    public String getTitle() {
        return title;
    }

    /**
     * @param title the new title; must not be null or blank
     * @throws IllegalArgumentException if the title is null or blank
     */
    public void setTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Title must not be blank.");
        }
        this.title = title.trim();
    }

    /** @return the notes, possibly null */
    public String getNotes() {
        return notes;
    }

    /** @param notes free text; null or empty are both acceptable */
    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** @return 0 if unrated, otherwise 1 to 5 */
    public int getRating() {
        return rating;
    }

    /**
     * @param rating 0 to clear the rating, otherwise 1 to 5
     * @throws IllegalArgumentException if outside 0 to 5
     */
    public void setRating(int rating) {
        if (rating < 0 || rating > 5) {
            throw new IllegalArgumentException(
                    "Rating must be between 0 and 5, or 0 for unrated, but was " + rating + ".");
        }
        this.rating = rating;
    }

    /** @return true if this item is in the trash */
    public boolean isDeleted() {
        return deleted;
    }

    /** @param deleted true to move to the trash, false to restore */
    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    // ---------------------------------------------------------------
    // Categories
    // ---------------------------------------------------------------

    /** @return an unmodifiable view of the category ids */
    public List<String> getCategoryIds() {
        return Collections.unmodifiableList(categoryIds);
    }

    /**
     * Files this item under a category. Doing it twice has no effect.
     *
     * @param categoryId the category's id; must not be blank
     * @throws IllegalArgumentException if the id is null or blank
     */
    public void addCategoryId(String categoryId) {
        if (categoryId == null || categoryId.trim().isEmpty()) {
            throw new IllegalArgumentException("Category id must not be blank.");
        }
        if (!categoryIds.contains(categoryId)) {
            categoryIds.add(categoryId);
        }
    }

    /**
     * Removes this item from a category. Removing one it is not in is
     * harmless, so callers need not check first.
     *
     * @param categoryId the category's id
     * @return true if the item was actually filed there
     */
    public boolean removeCategoryId(String categoryId) {
        return categoryIds.remove(categoryId);
    }

    // ---------------------------------------------------------------
    // Custom fields
    // ---------------------------------------------------------------

    /** @return an unmodifiable view of the user-defined fields */
    public Map<String, String> getCustomFields() {
        return Collections.unmodifiableMap(customFields);
    }

    /**
     * Sets a user-defined field, replacing any previous value for that key.
     * <p>
     * Field names ignore case: "Signed" and "signed" are one field, as they
     * are in the database. Setting "signed" when "Signed" exists replaces
     * it, takes the new spelling, and keeps the field in its original
     * position - fields are shown in the order they were first added.
     *
     * @param key   the field name; must not be blank
     * @param value the value; may be null or empty
     * @throws IllegalArgumentException if the key is null or blank
     */
    public void setCustomField(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Custom field name must not be blank.");
        }
        String newKey = key.trim();
        String oldKey = findCustomFieldKey(newKey);
        if (oldKey == null || oldKey.equals(newKey)) {
            // New field, or the same spelling: put keeps an existing key's
            // position in a LinkedHashMap.
            customFields.put(newKey, value);
            return;
        }
        // Same field, new spelling. A LinkedHashMap cannot rename a key in
        // place, so rebuild it in the same order with the key swapped.
        List<String> keys = new ArrayList<>(customFields.keySet());
        List<String> values = new ArrayList<>(customFields.values());
        customFields.clear();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(oldKey)) {
                customFields.put(newKey, value);
            } else {
                customFields.put(keys.get(i), values.get(i));
            }
        }
    }

    /**
     * @param key the field name, in any case
     * @return the stored value, or null if that field is not set
     */
    public String getCustomField(String key) {
        String stored = findCustomFieldKey(key);
        return stored == null ? null : customFields.get(stored);
    }

    /**
     * @param key the field name, in any case
     * @return true if a field with that name existed and was removed
     */
    public boolean removeCustomField(String key) {
        String stored = findCustomFieldKey(key);
        if (stored == null) {
            return false;
        }
        customFields.remove(stored);
        return true;
    }

    /**
     * Finds the stored spelling of a field name, ignoring case. A walk over
     * the keys rather than a map lookup, because a {@code HashMap} compares
     * keys exactly; an item has only a handful of fields.
     *
     * @param key the name to look for; may be null
     * @return the key as stored, or null if there is no such field
     */
    private String findCustomFieldKey(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.trim();
        List<String> keys = new ArrayList<>(customFields.keySet());
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equalsIgnoreCase(wanted)) {
                return keys.get(i);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Identity
    // ---------------------------------------------------------------

    /**
     * Two items are the same item when they share an id.
     * <p>
     * Identity is deliberately the {@code id} alone and not the title or
     * ISBN: two separately-acquired copies entered as separate books are
     * different items even with identical details, and an item stays itself
     * after being retitled.
     * <p>
     * This is what makes {@code HashMap<String, CollectionItem>} and
     * {@code HashSet} behave correctly - they rely on equals and hashCode
     * agreeing, which is why both are overridden together and never one
     * alone.
     *
     * @param other the object to compare with
     * @return true if {@code other} is a CollectionItem with the same id
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CollectionItem)) {
            return false;
        }
        CollectionItem that = (CollectionItem) other;
        return id.equals(that.id);
    }

    /**
     * Derived from the id alone, matching {@link #equals}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return the one-line summary, so debug output is readable */
    @Override
    public String toString() {
        return getDisplaySummary();
    }
}
