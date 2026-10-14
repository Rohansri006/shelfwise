package com.shelfwise.core.service;

import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
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
     * Finds a book by ISBN, in either written form: "978-0-14-303105-5"
     * finds a book stored as "9780143031055".
     *
     * @param isbn the ISBN to look for
     * @return the book, trashed or not, or null if there is none
     */
    public Book findBookByIsbn(String isbn) {
        String wanted = IsbnValidator.normalise(isbn);
        if (wanted == null || wanted.isEmpty()) {
            return null;
        }
        List<CollectionItem> all = allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) instanceof Book) {
                Book book = (Book) all.get(i);
                if (wanted.equals(book.getIsbn())) {
                    return book;
                }
            }
        }
        return null;
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
        itemsById.remove(id);
    }

    /**
     * Permanently deletes everything in the trash.
     *
     * @return how many items were deleted
     */
    public int emptyTrash() {
        List<CollectionItem> trash = getTrash();
        for (int i = 0; i < trash.size(); i++) {
            itemsById.remove(trash.get(i).getId());
        }
        return trash.size();
    }

    // ------------------------------------------------------------------
    // Searching - the four Searchable overloads
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * <p>
     * An ISBN typed with hyphens still matches, because the query is also
     * compared in normalised form against the stored, normalised ISBN.
     */
    @Override
    public List<CollectionItem> search(String text) {
        List<CollectionItem> live = getItems();
        if (text == null || text.trim().isEmpty()) {
            return live;
        }
        String query = text.trim().toLowerCase(Locale.ROOT);
        String isbnQuery = IsbnValidator.normalise(text.trim());

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
