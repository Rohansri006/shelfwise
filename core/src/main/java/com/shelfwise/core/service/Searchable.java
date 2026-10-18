package com.shelfwise.core.service;

import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingStatus;

import java.util.List;

/**
 * Something that can find items in a collection.
 * <p>
 * Four methods share one name and differ only in their parameter type - the
 * project's clearest example of <strong>overloading</strong>. The compiler
 * picks which one runs from the argument's declared type when the code is
 * compiled; contrast overriding, where the object's real class picks at
 * runtime.
 * <p>
 * Every overload leaves out items in the trash.
 */
public interface Searchable {

    /**
     * Free-text search across title, subtitle, ISBN, contributor names,
     * publisher and custom field values, ignoring case.
     *
     * @param text what to look for; blank returns every item
     * @return the matching items, possibly empty, never null
     */
    List<CollectionItem> search(String text);

    /**
     * Items filed under a category or any category beneath it, so choosing
     * "Fiction" includes "Fiction &gt; Science fiction".
     *
     * @param category the category
     * @return the matching items, possibly empty, never null
     */
    List<CollectionItem> search(Category category);

    /**
     * Items with a copy kept in a location or anywhere inside it, so
     * choosing a shelf includes every row on it.
     *
     * @param location the location
     * @return the matching items, possibly empty, never null
     */
    List<CollectionItem> search(Location location);

    /**
     * Items at a given reading status.
     *
     * @param status the status
     * @return the matching items, possibly empty, never null
     */
    List<CollectionItem> search(ReadingStatus status);
}
