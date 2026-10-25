package com.shelfwise.core.storage;

import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionData;
import com.shelfwise.core.service.CollectionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges what an {@link Importer} read into the collection (D17, ADR 0004).
 * <p>
 * Importing never replaces: it adds what is new and leaves alone what is
 * already there, so importing the same file twice changes nothing.
 * <ul>
 *   <li><strong>Books</strong> already present by id or ISBN are skipped
 *       and listed in the {@link ImportReport}, so the user can add their
 *       copies to the existing book instead.</li>
 *   <li><strong>Categories and locations</strong> are matched by full
 *       path, the way a person reads them: the file's "Fiction > Fantasy"
 *       is the collection's "Fiction > Fantasy" whatever ids either has.
 *       Missing levels are created.</li>
 *   <li><strong>Series and wishlists</strong> are matched by name, the
 *       rule that already makes their names unique. An existing wishlist
 *       gains only the entries it does not have (same title and ISBN).</li>
 *   <li>Books from the trash arrive in the trash.</li>
 * </ul>
 * Because the file's tree nodes and series are matched rather than copied,
 * the ids the incoming books point at are translated to the collection's
 * own before each book is filed. Every change goes through
 * {@link CollectionManager} methods, so the collection-wide rules hold
 * (ADR 0006).
 * <p>
 * The currency is not checked here: the screen checks
 * {@link ParsedImport#currencyDiffersFrom} first and asks the user.
 */
public class CollectionMerger {

    /**
     * Merges an import into the collection.
     *
     * @param parsed  what an importer read
     * @param manager the collection to merge into
     * @return what was added, skipped and refused
     * @throws IllegalArgumentException if either argument is null
     */
    public ImportReport merge(ParsedImport parsed, CollectionManager manager) {
        if (parsed == null || manager == null) {
            throw new IllegalArgumentException("Nothing to merge, or nowhere to merge it.");
        }
        ImportReport report = new ImportReport();
        for (int i = 0; i < parsed.getProblems().size(); i++) {
            report.problem(parsed.getProblems().get(i));
        }
        CollectionData data = parsed.getData();

        // Incoming id to the collection's matching node, for every node.
        Map<String, Category> categories = new HashMap<>();
        for (int i = 0; i < data.getCategories().size(); i++) {
            resolveCategory(data.getCategories().get(i), manager, categories, report);
        }
        Map<String, Location> locations = new HashMap<>();
        for (int i = 0; i < data.getLocations().size(); i++) {
            resolveLocation(data.getLocations().get(i), manager, locations, report);
        }
        Map<String, Series> series = new HashMap<>();
        for (int i = 0; i < data.getSeries().size(); i++) {
            Series incoming = data.getSeries().get(i);
            series.put(incoming.getId(), resolveSeries(incoming, manager, report));
        }
        for (int i = 0; i < data.getWishlists().size(); i++) {
            mergeWishlist(data.getWishlists().get(i), manager, report);
        }
        for (int i = 0; i < data.getBooks().size(); i++) {
            mergeBook(data.getBooks().get(i), manager, categories, locations, series, report);
        }
        return report;
    }

    /**
     * Adds the copies of a skipped book to the book the collection already
     * has: the "add as copy" choice the import screen offers (D17).
     * <p>
     * Each copy gets a fresh id, because the file's copy may be the very
     * copy the collection already holds - the same file imported twice -
     * and two copies must never share one.
     *
     * @param skipped a book from {@link ImportReport#getSkippedBooks()}
     * @param manager the collection the report came from
     * @return how many copies were added
     */
    public int addAsCopies(ImportReport.SkippedBook skipped, CollectionManager manager) {
        List<Copy> copies = skipped.getIncoming().getCopies();
        for (int i = 0; i < copies.size(); i++) {
            Copy from = copies.get(i);
            Copy copy = new Copy(from.getFormat(), from.getCondition());
            copy.setPurchaseDate(from.getPurchaseDate());
            copy.setPricePaid(from.getPricePaid());
            // Already translated to the collection's location during merge.
            Location location = manager.findLocationById(from.getLocationId());
            skipped.getExisting().addCopy(copy);
            manager.setLocation(copy, location);
        }
        return copies.size();
    }

    // ------------------------------------------------------------------
    // Books
    // ------------------------------------------------------------------

    /**
     * Adds one book, or records why not.
     * <p>
     * The book's references are taken off it first, the book is added, and
     * then each reference is put back through the manager's own methods
     * with the collection's ids.
     */
    private void mergeBook(Book book, CollectionManager manager, Map<String, Category> categories,
                           Map<String, Location> locations, Map<String, Series> series,
                           ImportReport report) {
        // Copies point at locations: translate them even for a skipped
        // book, so "add as copy" can use them later.
        List<Copy> copies = book.getCopies();
        List<Location> copyLocations = new ArrayList<>();
        for (int i = 0; i < copies.size(); i++) {
            Location target = locations.get(copies.get(i).getLocationId());
            copyLocations.add(target);
            copies.get(i).setLocationId(target == null ? null : target.getId());
        }

        Book existing = manager.findItemById(book.getId()) instanceof Book
                ? (Book) manager.findItemById(book.getId()) : null;
        if (existing == null && book.hasIsbn()) {
            existing = manager.findBookByIsbn(book.getIsbn());
        }
        if (existing != null) {
            report.bookSkipped(book, existing);
            return;
        }

        List<String> categoryIds = new ArrayList<>(book.getCategoryIds());
        for (int i = 0; i < categoryIds.size(); i++) {
            book.removeCategoryId(categoryIds.get(i));
        }
        Series bookSeries = series.get(book.getSeriesId());
        int position = book.getSeriesPosition();
        book.setSeriesId(null);
        try {
            manager.addItem(book);
        } catch (DuplicateItemException | IllegalArgumentException e) {
            // A duplicate is ruled out by the checks above, and the importer
            // already validated the book; kept so a gap in either shows up
            // as a report line rather than a crash.
            report.problem("\"" + book.getTitle() + "\": " + e.getMessage());
            return;
        }
        for (int i = 0; i < categoryIds.size(); i++) {
            Category target = categories.get(categoryIds.get(i));
            if (target != null) {
                manager.fileUnder(book, target);
            }
        }
        for (int i = 0; i < copies.size(); i++) {
            manager.setLocation(copies.get(i), copyLocations.get(i));
        }
        if (bookSeries != null) {
            manager.setSeries(book, bookSeries);
            book.setSeriesPosition(position);
        }
        report.bookAdded();
    }

    // ------------------------------------------------------------------
    // Trees, matched by path
    // ------------------------------------------------------------------

    /**
     * Finds the collection's category at the same path as an incoming one,
     * creating it (and any missing parents) if there is none. Parents are
     * resolved first, by calling this method on them - recursion - and the
     * map remembers every answer, so each node is resolved once.
     *
     * @param incoming a node from the file
     * @param manager  the collection
     * @param resolved incoming id to the collection's node, filled as it goes
     * @param report   counts created nodes
     * @return the collection's node at that path
     */
    private Category resolveCategory(Category incoming, CollectionManager manager,
                                     Map<String, Category> resolved, ImportReport report) {
        Category known = resolved.get(incoming.getId());
        if (known != null) {
            return known;
        }
        Category parent = incoming.getParent() == null
                ? null : resolveCategory(incoming.getParent(), manager, resolved, report);

        Category match = null;
        if (parent == null) {
            List<Category> roots = manager.getRootCategories();
            for (int i = 0; i < roots.size(); i++) {
                if (roots.get(i).getName().equalsIgnoreCase(incoming.getName())) {
                    match = roots.get(i);
                }
            }
        } else {
            match = parent.findChildByName(incoming.getName());
        }
        if (match == null) {
            match = new Category(incoming.getName());
            if (parent == null) {
                manager.addCategory(match);
            } else {
                manager.addCategory(match, parent);
            }
            report.categoryCreated();
        }
        resolved.put(incoming.getId(), match);
        return match;
    }

    /**
     * The location twin of {@link #resolveCategory}.
     *
     * @param incoming a node from the file
     * @param manager  the collection
     * @param resolved incoming id to the collection's node, filled as it goes
     * @param report   counts created nodes
     * @return the collection's node at that path
     */
    private Location resolveLocation(Location incoming, CollectionManager manager,
                                     Map<String, Location> resolved, ImportReport report) {
        Location known = resolved.get(incoming.getId());
        if (known != null) {
            return known;
        }
        Location parent = incoming.getParent() == null
                ? null : resolveLocation(incoming.getParent(), manager, resolved, report);

        Location match = null;
        if (parent == null) {
            List<Location> roots = manager.getRootLocations();
            for (int i = 0; i < roots.size(); i++) {
                if (roots.get(i).getName().equalsIgnoreCase(incoming.getName())) {
                    match = roots.get(i);
                }
            }
        } else {
            match = parent.findChildByName(incoming.getName());
        }
        if (match == null) {
            match = new Location(incoming.getName());
            if (parent == null) {
                manager.addLocation(match);
            } else {
                manager.addLocation(match, parent);
            }
            report.locationCreated();
        }
        resolved.put(incoming.getId(), match);
        return match;
    }

    // ------------------------------------------------------------------
    // Series and wishlists, matched by name
    // ------------------------------------------------------------------

    /**
     * @param incoming a series from the file
     * @param manager  the collection
     * @param report   counts new series
     * @return the collection's series of that name, added if missing
     */
    private Series resolveSeries(Series incoming, CollectionManager manager, ImportReport report) {
        List<Series> all = manager.getSeries();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getName().equalsIgnoreCase(incoming.getName())) {
                return all.get(i);
            }
        }
        // A fresh object rather than the file's, so its id cannot clash
        // with a series the collection has renamed since the export.
        Series added = new Series(incoming.getName(), incoming.getTotalBooks());
        manager.addSeries(added);
        report.seriesAdded();
        return added;
    }

    /**
     * Adds a wishlist the collection does not have, or the new entries of
     * one it does.
     *
     * @param incoming a wishlist from the file
     * @param manager  the collection
     * @param report   counts new wishlists and entries
     */
    private void mergeWishlist(Wishlist incoming, CollectionManager manager, ImportReport report) {
        Wishlist target = null;
        List<Wishlist> all = manager.getWishlists();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getName().equalsIgnoreCase(incoming.getName())) {
                target = all.get(i);
            }
        }
        if (target == null) {
            target = new Wishlist(incoming.getName());
            manager.addWishlist(target);
            report.wishlistAdded();
        }
        List<WishlistEntry> entries = incoming.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            WishlistEntry entry = entries.get(i);
            if (!hasEntry(target, entry)) {
                target.addEntry(copyOf(entry));
                report.wishlistEntryAdded();
            }
        }
    }

    /**
     * Same title and same ISBN (both ignoring case and hyphens) counts as
     * the same wish.
     *
     * @param wishlist the list to search
     * @param entry    the entry from the file
     * @return true if the list already has it
     */
    private static boolean hasEntry(Wishlist wishlist, WishlistEntry entry) {
        // E127: a list holds an ISBN once, so an entry with an ISBN already
        // there counts as present even under another title.
        if (wishlist.findEntryByIsbn(entry.getIsbn()) != null) {
            return true;
        }
        String isbn = isbnKey(entry.getIsbn());
        List<WishlistEntry> existing = wishlist.getEntries();
        for (int i = 0; i < existing.size(); i++) {
            WishlistEntry other = existing.get(i);
            if (other.getTitle().equalsIgnoreCase(entry.getTitle())
                    && isbn.equals(isbnKey(other.getIsbn()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param isbn an entry's ISBN as stored, possibly null or blank
     * @return it without hyphens or spaces, and "" for none, so a missing
     *         ISBN and a blank one compare equal
     */
    private static String isbnKey(String isbn) {
        return isbn == null ? "" : IsbnValidator.normalise(isbn);
    }

    /**
     * @param from an entry from the file
     * @return a new entry with a fresh id and the same details
     */
    private static WishlistEntry copyOf(WishlistEntry from) {
        WishlistEntry entry = new WishlistEntry(from.getTitle());
        entry.setIsbn(from.getIsbn());
        entry.setAuthor(from.getAuthor());
        entry.setPriority(from.getPriority());
        entry.setExpectedPrice(from.getExpectedPrice());
        entry.setStore(from.getStore());
        entry.setRare(from.isRare());
        return entry;
    }
}
