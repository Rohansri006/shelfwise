package com.shelfwise.core.service;

import com.shelfwise.core.exception.CategoryNotEmptyException;
import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.exception.LocationNotEmptyException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The collection held in memory, and the rules that span more than one
 * object.
 * <p>
 * Each model class guards its own fields - a {@link Book} rejects a bad
 * rating by itself. What a single object cannot check is anything that
 * needs to see the others: whether an ISBN is already in the collection,
 * whether a category still has books filed under it. Those rules live here.
 * <p>
 * Lookups go through {@link HashMap}s keyed by id, so finding an item is a
 * single step however large the collection grows. Each field is declared as
 * the {@link Map} interface and created as a {@code HashMap}: the code below
 * relies only on what any {@code Map} can do, so the concrete class could be
 * swapped without changing it.
 * <p>
 * This class never touches a file or database. It hands a
 * {@link CollectionData} snapshot to whichever {@link StorageProvider} is in
 * use and is built back from one, which keeps it testable without any
 * storage at all.
 */
public class CollectionManager implements Searchable {

    /** Every item, trashed ones included, keyed by id. */
    private final Map<String, CollectionItem> itemsById;

    /**
     * Every book that has an ISBN, trashed ones included, keyed by its
     * ISBN-13. A second index over the same books as {@link #itemsById}: it
     * turns the duplicate check on every add and every scan into one
     * {@code get} instead of a walk through the whole collection. It must be
     * kept in step wherever a book arrives, leaves or changes ISBN.
     */
    private final Map<String, Book> booksByIsbn;

    /** Every category node at any depth, keyed by id. */
    private final Map<String, Category> categoriesById;

    /** Every location node at any depth, keyed by id. */
    private final Map<String, Location> locationsById;

    /** Every series, keyed by id. */
    private final Map<String, Series> seriesById;

    /** Every wishlist, in the order they were added. */
    private final List<Wishlist> wishlists;

    /** Creates an empty collection - the state on first run. */
    public CollectionManager() {
        this.itemsById = new HashMap<>();
        this.booksByIsbn = new HashMap<>();
        this.categoriesById = new HashMap<>();
        this.locationsById = new HashMap<>();
        this.seriesById = new HashMap<>();
        this.wishlists = new ArrayList<>();
    }

    /**
     * Rebuilds the collection from a loaded snapshot.
     * <p>
     * The snapshot's categories and locations arrive with their parent and
     * child links already wired by the storage layer, so they only need
     * indexing here. Loaded data is trusted: the duplicate checks in
     * {@link #addItem(CollectionItem)} guard what the user adds, while the
     * database's own constraints guard what was stored.
     *
     * @param data what storage loaded; must not be null
     * @throws IllegalArgumentException if data is null
     */
    public CollectionManager(CollectionData data) {
        this();
        if (data == null) {
            throw new IllegalArgumentException("Collection data must not be null.");
        }
        for (int i = 0; i < data.getBooks().size(); i++) {
            Book book = data.getBooks().get(i);
            itemsById.put(book.getId(), book);
            indexIsbn(book);
        }
        for (int i = 0; i < data.getCategories().size(); i++) {
            Category category = data.getCategories().get(i);
            categoriesById.put(category.getId(), category);
        }
        for (int i = 0; i < data.getLocations().size(); i++) {
            Location location = data.getLocations().get(i);
            locationsById.put(location.getId(), location);
        }
        for (int i = 0; i < data.getSeries().size(); i++) {
            Series s = data.getSeries().get(i);
            seriesById.put(s.getId(), s);
        }
        wishlists.addAll(data.getWishlists());
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    /**
     * Adds an item to the collection.
     * <p>
     * The item checks its own whole-object rules first through
     * {@link CollectionItem#validate()}. The call is made through the
     * abstract parent type, and the overriding version in the item's real
     * class is the one that runs.
     * <p>
     * A book whose ISBN is already present is refused, <strong>even if the
     * existing one is in the trash</strong>. Otherwise restoring it later
     * would leave two books with one ISBN. The exception carries the
     * existing item's id so the screen can offer "add another copy to it"
     * instead.
     *
     * @param item the item to add; must not be null
     * @throws IllegalArgumentException if null, invalid, or already added
     * @throws DuplicateItemException   if a book with the same ISBN exists
     */
    public void addItem(CollectionItem item) throws DuplicateItemException {
        if (item == null) {
            throw new IllegalArgumentException("Item must not be null.");
        }
        if (itemsById.containsKey(item.getId())) {
            throw new IllegalArgumentException(
                    "\"" + item.getTitle() + "\" is already in the collection.");
        }
        item.validate();

        if (item instanceof Book) {
            Book book = (Book) item;
            if (book.hasIsbn()) {
                Book existing = findBookByIsbn(book.getIsbn());
                if (existing != null) {
                    throw new DuplicateItemException(
                            book.getIsbn(), existing.getId(), existing.getTitle());
                }
            }
        }
        itemsById.put(item.getId(), item);
        indexIsbn(item);
    }

    /**
     * Records a book in the ISBN index, if it is a book with an ISBN.
     *
     * @param item the item just added or loaded
     */
    private void indexIsbn(CollectionItem item) {
        if (item instanceof Book) {
            Book book = (Book) item;
            if (book.hasIsbn()) {
                booksByIsbn.put(book.getIsbn(), book);
            }
        }
    }

    /**
     * Removes an item from both indexes - the one place that does, so the
     * two maps cannot drift apart when items are deleted.
     *
     * @param item the item to forget
     */
    private void forgetItem(CollectionItem item) {
        itemsById.remove(item.getId());
        if (item instanceof Book) {
            Book book = (Book) item;
            if (book.hasIsbn()) {
                booksByIsbn.remove(book.getIsbn());
            }
        }
    }

    /**
     * Creates and adds a book with only a title - the quickest manual entry.
     *
     * @param title the title; must not be blank
     * @return the new book
     * @throws IllegalArgumentException if the title is blank
     * @throws DuplicateItemException   never in practice, since the book has
     *                                  no ISBN; declared because the core
     *                                  overload declares it
     */
    public Book addItem(String title) throws DuplicateItemException {
        Book book = new Book(title);
        addItem(book);
        return book;
    }

    /**
     * Creates and adds a book from an ISBN and title - what a scan produces.
     *
     * @param isbn  the ISBN, hyphens allowed
     * @param title the title; must not be blank
     * @return the new book
     * @throws IllegalArgumentException if the title is blank
     * @throws InvalidISBNException     if the ISBN fails its checksum
     * @throws DuplicateItemException   if a book with this ISBN exists
     */
    public Book addItem(String isbn, String title)
            throws InvalidISBNException, DuplicateItemException {
        Book book = new Book(isbn, title);
        addItem(book);
        return book;
    }

    /**
     * @param id the item's id
     * @return the item, trashed or not, or null if there is none
     */
    public CollectionItem findItemById(String id) {
        if (id == null) {
            return null;
        }
        return itemsById.get(id);
    }

    /**
     * Finds a book by ISBN, in any written form: "978-0-14-303105-5" and the
     * ISBN-10 "0143031058" both find a book stored as "9780143031055".
     *
     * @param isbn the ISBN to look for
     * @return the book, trashed or not, or null if there is none
     */
    public Book findBookByIsbn(String isbn) {
        // Books store ISBN-13 (see Book.setIsbn), so the search key must be
        // converted the same way before comparing.
        String wanted;
        try {
            wanted = IsbnValidator.toIsbn13(isbn);
        } catch (InvalidISBNException e) {
            // Blank or invalid: no stored book can have it, since every
            // stored ISBN passed validation.
            return null;
        }
        // One HashMap lookup, however large the collection.
        return booksByIsbn.get(wanted);
    }

    /**
     * Items not in the trash, in no particular order - a {@code HashMap}
     * keeps none, so screens sort the list themselves.
     *
     * @return a new list of live items
     */
    public List<CollectionItem> getItems() {
        return itemsWithDeleted(false);
    }

    /** @return a new list of the items in the trash */
    public List<CollectionItem> getTrash() {
        return itemsWithDeleted(true);
    }

    /**
     * Moves an item to the trash. It stays in the collection and can be
     * restored; only {@link #deletePermanently(String)} removes it.
     *
     * @param id the item's id
     * @throws IllegalArgumentException if there is no such item
     */
    public void moveToTrash(String id) {
        requireItem(id).setDeleted(true);
    }

    /**
     * Brings an item back out of the trash.
     *
     * @param id the item's id
     * @throws IllegalArgumentException if there is no such item
     */
    public void restoreFromTrash(String id) {
        requireItem(id).setDeleted(false);
    }

    /**
     * Removes an item for good.
     * <p>
     * Only an item already in the trash can be deleted, so destroying data
     * always takes two deliberate steps.
     *
     * @param id the item's id
     * @throws IllegalArgumentException if there is no such item, or it is
     *                                  not in the trash
     */
    public void deletePermanently(String id) {
        CollectionItem item = requireItem(id);
        if (!item.isDeleted()) {
            throw new IllegalArgumentException(
                    "\"" + item.getTitle() + "\" must be moved to the trash before it can be deleted.");
        }
        forgetItem(item);
    }

    /**
     * Permanently deletes everything in the trash.
     *
     * @return how many items were deleted
     */
    public int emptyTrash() {
        List<CollectionItem> trash = getTrash();
        for (int i = 0; i < trash.size(); i++) {
            forgetItem(trash.get(i));
        }
        return trash.size();
    }

    // ------------------------------------------------------------------
    // Categories
    //
    // The tree class already refuses duplicate sibling names and cycles
    // inside addChild. What it cannot refuse is two *top-level* categories
    // with the same name, because a root has no parent to check its
    // siblings. That is the same gap the database had - UNIQUE
    // (parent_id, name) ignores rows whose parent_id is NULL - and it is
    // closed here the same way, by checking roots separately.
    //
    // The location methods below repeat this pattern for Location. The two
    // tree classes deliberately share no base class (see CLAUDE.md §10), so
    // the manager has one set of methods for each.
    // ------------------------------------------------------------------

    /**
     * Adds a top-level category, together with any sub-categories already
     * attached beneath it.
     *
     * @param category the category; must be top-level
     * @throws IllegalArgumentException if null, already added, not
     *                                  top-level, or another top-level
     *                                  category has the same name
     */
    public void addCategory(Category category) {
        requireNewCategory(category);
        if (!category.isRoot()) {
            throw new IllegalArgumentException(
                    "\"" + category.getName() + "\" has a parent; add it under that parent instead.");
        }
        requireUniqueRootCategoryName(category.getName(), category);
        indexCategorySubtree(category);
    }

    /**
     * Adds a category beneath an existing one.
     *
     * @param category the new category
     * @param parent   an existing category to put it under
     * @throws IllegalArgumentException if either is invalid, or the parent
     *                                  already has a child of that name
     */
    public void addCategory(Category category, Category parent) {
        requireNewCategory(category);
        requireKnownCategory(parent);
        parent.addChild(category);
        indexCategorySubtree(category);
    }

    /**
     * Moves a category, with everything beneath it, to a new parent.
     *
     * @param category  an existing category
     * @param newParent the new parent, or null to make it top-level
     * @throws IllegalArgumentException if the move would put it inside
     *                                  itself or clash with a name already
     *                                  there
     */
    public void moveCategory(Category category, Category newParent) {
        requireKnownCategory(category);
        if (newParent == null) {
            if (category.isRoot()) {
                return;
            }
            requireUniqueRootCategoryName(category.getName(), category);
            category.getParent().removeChild(category);
        } else {
            requireKnownCategory(newParent);
            newParent.addChild(category);
        }
    }

    /**
     * Deletes a category and every sub-category beneath it.
     * <p>
     * Refused while any item is filed anywhere in that subtree - the same
     * rule the database enforces with {@code ON DELETE RESTRICT}. Items in
     * the trash count too, because they are still stored and the database
     * would refuse the delete because of them.
     *
     * @param category an existing category
     * @throws CategoryNotEmptyException if items are filed under it
     * @throws IllegalArgumentException  if the category is not known
     */
    public void deleteCategory(Category category) throws CategoryNotEmptyException {
        requireKnownCategory(category);
        List<Category> subtree = category.getSubtree();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < subtree.size(); i++) {
            ids.add(subtree.get(i).getId());
        }

        int itemCount = 0;
        List<CollectionItem> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            if (countMatchingIds(all.get(i).getCategoryIds(), ids) > 0) {
                itemCount++;
            }
        }
        if (itemCount > 0) {
            throw new CategoryNotEmptyException(category.getName(), itemCount);
        }

        if (category.getParent() != null) {
            category.getParent().removeChild(category);
        }
        for (int i = 0; i < subtree.size(); i++) {
            categoriesById.remove(subtree.get(i).getId());
        }
    }

    /**
     * @param id the category's id
     * @return the category, or null if there is none
     */
    public Category findCategoryById(String id) {
        if (id == null) {
            return null;
        }
        return categoriesById.get(id);
    }

    /** @return a new list of the top-level categories, in no particular order */
    public List<Category> getRootCategories() {
        List<Category> roots = new ArrayList<>();
        List<Category> all = new ArrayList<>(categoriesById.values());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isRoot()) {
                roots.add(all.get(i));
            }
        }
        return roots;
    }

    /**
     * @param category the category to check
     * @throws IllegalArgumentException if null or already added
     */
    private void requireNewCategory(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("Category must not be null.");
        }
        if (categoriesById.containsKey(category.getId())) {
            throw new IllegalArgumentException(
                    "\"" + category.getName() + "\" has already been added.");
        }
    }

    /**
     * @param category the category to check
     * @throws IllegalArgumentException if null or not in this collection
     */
    private void requireKnownCategory(Category category) {
        if (category == null || !categoriesById.containsKey(category.getId())) {
            throw new IllegalArgumentException("That category is not in this collection.");
        }
    }

    /**
     * @param name   the proposed top-level name
     * @param ignore a category to leave out of the comparison (the one
     *               being moved), or null
     * @throws IllegalArgumentException if another root already has the name
     */
    private void requireUniqueRootCategoryName(String name, Category ignore) {
        List<Category> roots = getRootCategories();
        for (int i = 0; i < roots.size(); i++) {
            Category root = roots.get(i);
            if (root != ignore && root.getName().equalsIgnoreCase(name.trim())) {
                throw new IllegalArgumentException(
                        "A top-level category called \"" + root.getName() + "\" already exists.");
            }
        }
    }

    /** @param category the root of the subtree to index */
    private void indexCategorySubtree(Category category) {
        List<Category> subtree = category.getSubtree();
        for (int i = 0; i < subtree.size(); i++) {
            categoriesById.put(subtree.get(i).getId(), subtree.get(i));
        }
    }

    // ------------------------------------------------------------------
    // Locations - the same rules as categories, but a location is "not
    // empty" when copies are kept in it, not when items are filed under it.
    // ------------------------------------------------------------------

    /**
     * Adds a top-level location, together with anything already inside it -
     * so a shelf from {@link Location#createShelfWithRows} arrives with its
     * rows.
     *
     * @param location the location; must be top-level
     * @throws IllegalArgumentException if null, already added, not
     *                                  top-level, or another top-level
     *                                  location has the same name
     */
    public void addLocation(Location location) {
        requireNewLocation(location);
        if (!location.isRoot()) {
            throw new IllegalArgumentException(
                    "\"" + location.getName() + "\" is inside another location; add it there instead.");
        }
        requireUniqueRootLocationName(location.getName(), location);
        indexLocationSubtree(location);
    }

    /**
     * Adds a location inside an existing one.
     *
     * @param location the new location
     * @param parent   an existing location to put it in
     * @throws IllegalArgumentException if either is invalid, or the parent
     *                                  already contains one of that name
     */
    public void addLocation(Location location, Location parent) {
        requireNewLocation(location);
        requireKnownLocation(parent);
        parent.addChild(location);
        indexLocationSubtree(location);
    }

    /**
     * Moves a location, with everything inside it, to a new parent. Copies
     * travel with it, since they refer to it by id.
     *
     * @param location  an existing location
     * @param newParent the new parent, or null to make it top-level
     * @throws IllegalArgumentException if the move would put it inside
     *                                  itself or clash with a name already
     *                                  there
     */
    public void moveLocation(Location location, Location newParent) {
        requireKnownLocation(location);
        if (newParent == null) {
            if (location.isRoot()) {
                return;
            }
            requireUniqueRootLocationName(location.getName(), location);
            location.getParent().removeChild(location);
        } else {
            requireKnownLocation(newParent);
            newParent.addChild(location);
        }
    }

    /**
     * Deletes a location and everything inside it.
     * <p>
     * Refused while any copy is kept anywhere inside, trashed books'
     * copies included, for the same reason as
     * {@link #deleteCategory(Category)}.
     *
     * @param location an existing location
     * @throws LocationNotEmptyException if copies are kept inside it
     * @throws IllegalArgumentException  if the location is not known
     */
    public void deleteLocation(Location location) throws LocationNotEmptyException {
        requireKnownLocation(location);
        List<Location> subtree = location.getSubtree();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < subtree.size(); i++) {
            ids.add(subtree.get(i).getId());
        }

        int copyCount = 0;
        List<CollectionItem> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof Book) {
                copyCount += countCopiesIn((Book) all.get(i), ids);
            }
        }
        if (copyCount > 0) {
            throw new LocationNotEmptyException(location.getName(), copyCount);
        }

        if (location.getParent() != null) {
            location.getParent().removeChild(location);
        }
        for (int i = 0; i < subtree.size(); i++) {
            locationsById.remove(subtree.get(i).getId());
        }
    }

    /**
     * @param id the location's id
     * @return the location, or null if there is none
     */
    public Location findLocationById(String id) {
        if (id == null) {
            return null;
        }
        return locationsById.get(id);
    }

    /** @return a new list of the top-level locations, in no particular order */
    public List<Location> getRootLocations() {
        List<Location> roots = new ArrayList<>();
        List<Location> all = new ArrayList<>(locationsById.values());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isRoot()) {
                roots.add(all.get(i));
            }
        }
        return roots;
    }

    /**
     * @param location the location to check
     * @throws IllegalArgumentException if null or already added
     */
    private void requireNewLocation(Location location) {
        if (location == null) {
            throw new IllegalArgumentException("Location must not be null.");
        }
        if (locationsById.containsKey(location.getId())) {
            throw new IllegalArgumentException(
                    "\"" + location.getName() + "\" has already been added.");
        }
    }

    /**
     * @param location the location to check
     * @throws IllegalArgumentException if null or not in this collection
     */
    private void requireKnownLocation(Location location) {
        if (location == null || !locationsById.containsKey(location.getId())) {
            throw new IllegalArgumentException("That location is not in this collection.");
        }
    }

    /**
     * @param name   the proposed top-level name
     * @param ignore a location to leave out of the comparison, or null
     * @throws IllegalArgumentException if another root already has the name
     */
    private void requireUniqueRootLocationName(String name, Location ignore) {
        List<Location> roots = getRootLocations();
        for (int i = 0; i < roots.size(); i++) {
            Location root = roots.get(i);
            if (root != ignore && root.getName().equalsIgnoreCase(name.trim())) {
                throw new IllegalArgumentException(
                        "A top-level location called \"" + root.getName() + "\" already exists.");
            }
        }
    }

    /** @param location the root of the subtree to index */
    private void indexLocationSubtree(Location location) {
        List<Location> subtree = location.getSubtree();
        for (int i = 0; i < subtree.size(); i++) {
            locationsById.put(subtree.get(i).getId(), subtree.get(i));
        }
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    /**
     * Adds a series. Names are unique across the collection, ignoring case,
     * matching the database's {@code UNIQUE} constraint.
     *
     * @param series the series
     * @throws IllegalArgumentException if null, already added, or the name
     *                                  is taken
     */
    public void addSeries(Series series) {
        if (series == null) {
            throw new IllegalArgumentException("Series must not be null.");
        }
        if (seriesById.containsKey(series.getId())) {
            throw new IllegalArgumentException(
                    "\"" + series.getName() + "\" has already been added.");
        }
        List<Series> all = getSeries();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getName().equalsIgnoreCase(series.getName())) {
                throw new IllegalArgumentException(
                        "A series called \"" + all.get(i).getName() + "\" already exists.");
            }
        }
        seriesById.put(series.getId(), series);
    }

    /** @return a new list of every series, in no particular order */
    public List<Series> getSeries() {
        return new ArrayList<>(seriesById.values());
    }

    /**
     * @param id the series' id
     * @return the series, or null if there is none
     */
    public Series findSeriesById(String id) {
        if (id == null) {
            return null;
        }
        return seriesById.get(id);
    }

    /**
     * Deletes a series. Its books stay in the collection, simply no longer
     * in a series - the Java side of the database's
     * {@code ON DELETE SET NULL}. Clearing a book's series id also clears
     * its position (see {@link Book#setSeriesId}).
     *
     * @param series an existing series
     * @return how many books were taken out of it
     * @throws IllegalArgumentException if the series is not known
     */
    public int deleteSeries(Series series) {
        if (series == null || !seriesById.containsKey(series.getId())) {
            throw new IllegalArgumentException("That series is not in this collection.");
        }
        int released = 0;
        List<CollectionItem> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof Book) {
                Book book = (Book) all.get(i);
                if (series.getId().equals(book.getSeriesId())) {
                    book.setSeriesId(null);
                    released++;
                }
            }
        }
        seriesById.remove(series.getId());
        return released;
    }

    // ------------------------------------------------------------------
    // Wishlists
    // ------------------------------------------------------------------

    /**
     * Adds a wishlist. Names are unique, ignoring case, matching the
     * database's {@code UNIQUE} constraint.
     *
     * @param wishlist the wishlist
     * @throws IllegalArgumentException if null, already added, or the name
     *                                  is taken
     */
    public void addWishlist(Wishlist wishlist) {
        if (wishlist == null) {
            throw new IllegalArgumentException("Wishlist must not be null.");
        }
        if (wishlists.contains(wishlist)) {
            throw new IllegalArgumentException(
                    "\"" + wishlist.getName() + "\" has already been added.");
        }
        for (int i = 0; i < wishlists.size(); i++) {
            if (wishlists.get(i).getName().equalsIgnoreCase(wishlist.getName())) {
                throw new IllegalArgumentException(
                        "A wishlist called \"" + wishlists.get(i).getName() + "\" already exists.");
            }
        }
        wishlists.add(wishlist);
    }

    /** @return an unmodifiable view of the wishlists, in the order added */
    public List<Wishlist> getWishlists() {
        return Collections.unmodifiableList(wishlists);
    }

    /**
     * Removes a wishlist, and with it all its entries - they are part of
     * the list (composition), so they have nowhere else to go.
     *
     * @param wishlist the wishlist
     * @return true if it was present and removed
     */
    public boolean removeWishlist(Wishlist wishlist) {
        return wishlists.remove(wishlist);
    }

    // ------------------------------------------------------------------
    // Searching - the four Searchable overloads
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>
     * An ISBN typed with hyphens still matches, because the query is also
     * compared in normalised form against the stored, normalised ISBN. A
     * complete ISBN-10 matches too, because it is converted to the ISBN-13
     * form that books store.
     */
    @Override
    public List<CollectionItem> search(String text) {
        List<CollectionItem> live = getItems();
        if (text == null || text.trim().isEmpty()) {
            return live;
        }
        String query = text.trim().toLowerCase(Locale.ROOT);
        String isbnQuery = IsbnValidator.normalise(text.trim());
        try {
            isbnQuery = IsbnValidator.toIsbn13(isbnQuery);
        } catch (InvalidISBNException e) {
            // Not a complete ISBN (a title word, or part of a number): keep
            // it as typed so it still matches as a fragment.
        }

        List<CollectionItem> result = new ArrayList<>();
        for (int i = 0; i < live.size(); i++) {
            if (matchesText(live.get(i), query, isbnQuery)) {
                result.add(live.get(i));
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The subtree's ids go into a {@link HashSet} first, so testing each of
     * an item's categories is one quick {@code contains} rather than a walk
     * down the tree.
     *
     * @throws IllegalArgumentException if category is null
     */
    @Override
    public List<CollectionItem> search(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("Category must not be null.");
        }
        Set<String> wanted = new HashSet<>();
        List<Category> subtree = category.getSubtree();
        for (int i = 0; i < subtree.size(); i++) {
            wanted.add(subtree.get(i).getId());
        }

        List<CollectionItem> result = new ArrayList<>();
        List<CollectionItem> live = getItems();
        for (int i = 0; i < live.size(); i++) {
            if (countMatchingIds(live.get(i).getCategoryIds(), wanted) > 0) {
                result.add(live.get(i));
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>
     * A book matches if <em>any</em> of its copies is inside the location,
     * because copies of one title can sit on different shelves.
     *
     * @throws IllegalArgumentException if location is null
     */
    @Override
    public List<CollectionItem> search(Location location) {
        if (location == null) {
            throw new IllegalArgumentException("Location must not be null.");
        }
        Set<String> wanted = new HashSet<>();
        List<Location> subtree = location.getSubtree();
        for (int i = 0; i < subtree.size(); i++) {
            wanted.add(subtree.get(i).getId());
        }

        List<CollectionItem> result = new ArrayList<>();
        List<CollectionItem> live = getItems();
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i) instanceof Book
                    && countCopiesIn((Book) live.get(i), wanted) > 0) {
                result.add(live.get(i));
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if status is null
     */
    @Override
    public List<CollectionItem> search(ReadingStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Reading status must not be null.");
        }
        List<CollectionItem> result = new ArrayList<>();
        List<CollectionItem> live = getItems();
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i) instanceof Book
                    && ((Book) live.get(i)).getReadingStatus() == status) {
                result.add(live.get(i));
            }
        }
        return result;
    }

    /**
     * Whether an item matches a free-text query. Every item is searched by
     * title; books are also searched by their own fields.
     *
     * @param item      the item to test
     * @param query     the query, lowercased
     * @param isbnQuery the query with hyphens and spaces removed
     * @return true if any searched field contains the query
     */
    private boolean matchesText(CollectionItem item, String query, String isbnQuery) {
        if (containsIgnoreCase(item.getTitle(), query)) {
            return true;
        }
        if (!(item instanceof Book)) {
            return false;
        }
        Book book = (Book) item;
        if (containsIgnoreCase(book.getSubtitle(), query)
                || containsIgnoreCase(book.getPublisher(), query)) {
            return true;
        }
        if (book.hasIsbn() && !isbnQuery.isEmpty() && book.getIsbn().contains(isbnQuery)) {
            return true;
        }
        List<Contributor> contributors = book.getContributors();
        for (int i = 0; i < contributors.size(); i++) {
            if (containsIgnoreCase(contributors.get(i).getName(), query)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Null-safe, case-insensitive "contains". {@code Locale.ROOT} keeps
     * lowercasing the same on every machine; under a Turkish locale, for
     * example, "I" would otherwise not lowercase to "i".
     *
     * @param field the field's value; may be null
     * @param query the query, already lowercased
     * @return true if the field contains the query
     */
    private static boolean containsIgnoreCase(String field, String query) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * @param ids    the ids to test
     * @param wanted the ids being looked for
     * @return how many of {@code ids} are in {@code wanted}
     */
    private static int countMatchingIds(List<String> ids, Set<String> wanted) {
        int count = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (wanted.contains(ids.get(i))) {
                count++;
            }
        }
        return count;
    }

    /**
     * @param book        the book
     * @param locationIds the location ids being looked for
     * @return how many of the book's copies are in one of those locations
     */
    private static int countCopiesIn(Book book, Set<String> locationIds) {
        int count = 0;
        List<Copy> copies = book.getCopies();
        for (int i = 0; i < copies.size(); i++) {
            String locationId = copies.get(i).getLocationId();
            if (locationId != null && locationIds.contains(locationId)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    /**
     * A snapshot of everything, ready for {@link StorageProvider#save}.
     * <p>
     * {@code itemsById} holds {@link CollectionItem}s, but storage saves
     * {@link Book}s, so each item is checked with {@code instanceof} before
     * the cast. The check is what makes the cast safe: if another item type
     * is ever added, it is skipped instead of crashing with a
     * {@code ClassCastException}.
     *
     * @return the snapshot, stamped with the current time
     */
    public CollectionData toCollectionData() {
        List<Book> books = new ArrayList<>();
        List<CollectionItem> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof Book) {
                books.add((Book) all.get(i));
            }
        }
        return new CollectionData(books,
                new ArrayList<>(categoriesById.values()),
                new ArrayList<>(locationsById.values()),
                new ArrayList<>(seriesById.values()),
                wishlists,
                Instant.now());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Every item as a list, so it can be walked with an indexed loop like
     * the rest of the codebase. A {@code Map} has no index of its own.
     *
     * @return a new list of every item, trashed included
     */
    private List<CollectionItem> allItems() {
        return new ArrayList<>(itemsById.values());
    }

    /**
     * @param deleted true for the trash, false for live items
     * @return a new list of the items in that state
     */
    private List<CollectionItem> itemsWithDeleted(boolean deleted) {
        List<CollectionItem> result = new ArrayList<>();
        List<CollectionItem> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isDeleted() == deleted) {
                result.add(all.get(i));
            }
        }
        return result;
    }

    /**
     * @param id the item's id
     * @return the item
     * @throws IllegalArgumentException if there is no such item
     */
    private CollectionItem requireItem(String id) {
        CollectionItem item = findItemById(id);
        if (item == null) {
            throw new IllegalArgumentException("No item with id " + id + ".");
        }
        return item;
    }
}
