package com.shelfwise.desktop.ui;

import com.shelfwise.core.metadata.LookupResult;
import com.shelfwise.core.metadata.MetadataLookup;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import javafx.scene.image.Image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Refresh details (E142-E148). Looks a book up
 * again and works out, field by field, what the lookup would change, so
 * the user can keep their value or take the found one.
 * <p>
 * Kept free of dialogs: the single review ({@link RefreshDialog}), the
 * bulk review ({@link BulkRefreshDialog}) and the Edit form all share the
 * same comparison and the same apply/undo code from here.
 * <p>
 * A chosen cover is written as the book's cover file (E153), and Undo puts
 * the old file back byte for byte, or removes the new one if there was
 * none (E147).
 */
final class RefreshDetails {

    /** E143: the fields a refresh may change. Never ISBN, copies, categories, reading or notes. */
    enum Field {
        TITLE("Title"), SUBTITLE("Subtitle"), AUTHORS("Authors"), PUBLISHER("Publisher"),
        YEAR("Year"), PAGES("Pages"), LANGUAGE("Language"), COVER("Cover");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** E146: what a bulk refresh does with one field. */
    enum Rule {
        FILL_EMPTY("Fill if empty"), REPLACE("Replace"), KEEP("Keep");

        private final String label;

        Rule(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What one lookup brought back: the found book, its cover and where it came from. */
    static class Found {
        final Book book;
        final Image cover;
        final String source;

        Found(Book book, Image cover, String source) {
            this.book = book;
            this.cover = cover;
            this.source = source;
        }
    }

    /**
     * One proposed change: a field whose found value differs from the
     * user's. {@code use} is the tick the user can clear to keep their own.
     */
    static class Change {
        /** The book it belongs to; null when the Edit form is the target. */
        final Book book;
        final Field field;
        final String yours;
        final String theirs;
        final Found found;
        boolean use;

        Change(Book book, Field field, String yours, String theirs, Found found, boolean use) {
            this.book = book;
            this.field = field;
            this.yours = yours;
            this.theirs = theirs;
            this.found = found;
            this.use = use;
        }

        /** "Pages: — → 328", for the bulk review list. */
        String describe() {
            return field.getLabel() + ":  " + shown(yours) + "  →  " + shown(theirs);
        }
    }

    /** "—" for an empty value, so an empty cell still reads as "nothing". */
    static String shown(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    /** A utility class is never instantiated. */
    private RefreshDetails() {
    }

    // ------------------------------------------------------------------
    // Lookup (call on a background thread: it may use the network)
    // ------------------------------------------------------------------

    /**
     * Looks a book's ISBN up, cache first (so cached books work offline).
     *
     * @param wantCover false skips the cover download, which is the slow part
     * @return what was found
     * @throws Exception when nothing was found; its message says why
     */
    static Found lookUp(MetadataLookup lookup, String isbn, boolean wantCover) throws Exception {
        LookupResult result = lookup.lookUp(isbn);
        Book book = result.toBook(null);
        Image cover = wantCover ? CoverArt.download(result.getMetadata().getCoverUrl()) : null;
        // describe(): "Found on Open Library [from cache]", the same line the Add form shows.
        return new Found(book, cover, result.describe());
    }

    // ------------------------------------------------------------------
    // Comparing
    // ------------------------------------------------------------------

    /**
     * Every field's value as text, the form both sides are compared in.
     * Numbers use "" for 0 (the model's "not recorded"), so an unknown page
     * count counts as empty.
     */
    static Map<Field, String> values(Book book) {
        Map<Field, String> map = new EnumMap<>(Field.class);
        map.put(Field.TITLE, text(book.getTitle()));
        map.put(Field.SUBTITLE, text(book.getSubtitle()));
        map.put(Field.AUTHORS, authors(book.getContributors()));
        map.put(Field.PUBLISHER, text(book.getPublisher()));
        map.put(Field.YEAR, number(book.getPublicationYear()));
        map.put(Field.PAGES, number(book.getPages()));
        map.put(Field.LANGUAGE, text(book.getLanguage()));
        return map;
    }

    /** E144: only the people credited as Author; translators and editors are not compared. */
    static String authors(List<Contributor> contributors) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < contributors.size(); i++) {
            if (contributors.get(i).getRole() == ContributorRole.AUTHOR) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(contributors.get(i).getName());
            }
        }
        return names.toString();
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String number(int value) {
        return value == 0 ? "" : String.valueOf(value);
    }

    /**
     * The changes a lookup proposes.
     *
     * @param book     the book, or null when comparing against the Edit form
     * @param yours    the user's current values, from {@link #values}
     * @param hasCover whether the user already has a real cover
     * @param found    the lookup's answer
     * @param rules    E146 per-field rules for a bulk refresh; null for the
     *                 single review, which ticks only fields the user left
     *                 empty (E145)
     * @return the changes, in field order; empty when nothing differs
     */
    static List<Change> diff(Book book, Map<Field, String> yours, boolean hasCover, Found found,
                             Map<Field, Rule> rules) {
        Map<Field, String> theirs = values(found.book);
        List<Change> changes = new ArrayList<>();
        Field[] fields = Field.values();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            String mine;
            String other;
            if (field == Field.COVER) {
                mine = hasCover ? "Your cover" : "";
                other = found.cover == null ? "" : "Found cover";
            } else {
                mine = yours.get(field);
                other = theirs.get(field);
            }
            // Nothing found: nothing to offer. The same text: nothing to change.
            // Two covers can never be compared, so they always count as different.
            if (other.isEmpty() || (field != Field.COVER && other.equals(mine))) {
                continue;
            }
            boolean empty = mine.isEmpty();
            if (rules == null) {
                changes.add(new Change(book, field, mine, other, found, empty));
            } else {
                Rule rule = rules.get(field);
                if (rule == Rule.REPLACE || (rule == Rule.FILL_EMPTY && empty)) {
                    changes.add(new Change(book, field, mine, other, found, true));
                }
            }
        }
        return changes;
    }

    /** @return how many changes are ticked */
    static int countUsed(List<Change> changes) {
        int n = 0;
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).use) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Applying and undoing
    // ------------------------------------------------------------------

    /**
     * Applies the ticked changes to their books (the details panel and bulk
     * paths). A snapshot of each book is taken first, for Undo (E147).
     *
     * @param dataFolder the collection's folder, where cover files live
     * @param problems   collects a line for each cover that could not be
     *                   written; the other changes still apply
     * @return one snapshot per changed book, to hand to {@link #undo}
     * @throws IOException if a book's old cover cannot be read for the snapshot
     */
    static List<Snapshot> apply(List<Change> changes, Path dataFolder, List<String> problems) throws IOException {
        List<Snapshot> snapshots = new ArrayList<>();
        List<Book> seen = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            if (!change.use) {
                continue;
            }
            if (!seen.contains(change.book)) {
                seen.add(change.book);
                snapshots.add(new Snapshot(change.book, dataFolder));
            }
            applyOne(change.book, change, dataFolder, problems);
        }
        return snapshots;
    }

    private static void applyOne(Book book, Change change, Path dataFolder, List<String> problems) {
        Book from = change.found.book;
        switch (change.field) {
            case TITLE:
                book.setTitle(from.getTitle());
                break;
            case SUBTITLE:
                book.setSubtitle(from.getSubtitle());
                break;
            case AUTHORS:
                replaceAuthors(book, from.getContributors());
                break;
            case PUBLISHER:
                book.setPublisher(from.getPublisher());
                break;
            case YEAR:
                book.setPublicationYear(from.getPublicationYear());
                break;
            case PAGES:
                book.setPages(from.getPages());
                break;
            case LANGUAGE:
                book.setLanguage(from.getLanguage());
                break;
            default:
                // COVER: written as the book's cover file (E153).
                try {
                    CoverArt.saveCover(book, dataFolder, change.found.cover);
                } catch (IOException e) {
                    problems.add("The cover of ‘" + book.getTitle() + "’ could not be saved: " + e.getMessage());
                }
                break;
        }
    }

    /**
     * E144: the found authors replace the book's authors and come first;
     * everyone credited in another role (translator, editor) is kept after them.
     */
    static void replaceAuthors(Book book, List<Contributor> found) {
        List<Contributor> others = new ArrayList<>();
        List<Contributor> all = new ArrayList<>(book.getContributors());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getRole() != ContributorRole.AUTHOR) {
                others.add(all.get(i));
            }
            book.removeContributor(all.get(i));
        }
        for (int i = 0; i < found.size(); i++) {
            book.addContributor(found.get(i));
        }
        for (int i = 0; i < others.size(); i++) {
            book.addContributor(others.get(i));
        }
    }

    /**
     * E147: puts every snapshot back.
     *
     * @throws IOException if a cover file cannot be put back; the other
     *                     books are still restored first
     */
    static void undo(List<Snapshot> snapshots) throws IOException {
        IOException failed = null;
        for (int i = 0; i < snapshots.size(); i++) {
            try {
                snapshots.get(i).restore();
            } catch (IOException e) {
                failed = e;
            }
        }
        if (failed != null) {
            throw failed;
        }
    }

    /**
     * Everything a refresh can change on one book, copied before the
     * change so Undo can put it back exactly. The contributor list is
     * copied, not referenced, because the book's own list changes. The
     * cover file's bytes are copied too (null when there was none): a few
     * hundred KB at most, and it makes Undo exact.
     */
    static class Snapshot {
        private final Book book;
        private final String title;
        private final String subtitle;
        private final String publisher;
        private final int year;
        private final int pages;
        private final String language;
        private final List<Contributor> contributors;
        private final Path coverFile;
        private final byte[] coverBytes;

        Snapshot(Book book, Path dataFolder) throws IOException {
            this.book = book;
            this.title = book.getTitle();
            this.subtitle = book.getSubtitle();
            this.publisher = book.getPublisher();
            this.year = book.getPublicationYear();
            this.pages = book.getPages();
            this.language = book.getLanguage();
            this.contributors = new ArrayList<>(book.getContributors());
            this.coverFile = CoverArt.uploadedPath(book, dataFolder);
            this.coverBytes = Files.exists(coverFile) ? Files.readAllBytes(coverFile) : null;
        }

        void restore() throws IOException {
            book.setTitle(title);
            book.setSubtitle(subtitle);
            book.setPublisher(publisher);
            book.setPublicationYear(year);
            book.setPages(pages);
            book.setLanguage(language);
            List<Contributor> now = new ArrayList<>(book.getContributors());
            for (int i = 0; i < now.size(); i++) {
                book.removeContributor(now.get(i));
            }
            for (int i = 0; i < contributors.size(); i++) {
                book.addContributor(contributors.get(i));
            }
            if (coverBytes == null) {
                // There was no cover file before: remove one the refresh wrote.
                Files.deleteIfExists(coverFile);
            } else {
                Files.write(coverFile, coverBytes);
            }
        }
    }

    /** @return true if the book shows a real cover (uploaded or cached), for {@link #diff}'s hasCover */
    static boolean hasCover(Book book, Path dataFolder) {
        return CoverArt.imageFor(book, dataFolder) != null;
    }
}
