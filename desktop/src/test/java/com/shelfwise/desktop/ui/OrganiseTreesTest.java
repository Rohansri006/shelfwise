package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.service.CollectionManager;
import com.shelfwise.core.storage.SettingsFile;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the Organise page's delete and Undo (E101, E102), done by
 * {@link CategoryTree} and {@link LocationTree}. They need no JavaFX: the
 * adapters work on the collection only. Each test empties a subtree, deletes
 * it, puts it back and runs the undo steps, then checks every filing or
 * shelf is exactly as it started - trashed books included, since the
 * delete has to free them too.
 */
class OrganiseTreesTest {

    @TempDir
    Path folder;

    private Session session;
    private CollectionManager manager;

    private Category fiction;
    private Category sciFi;
    private Category space;
    /** Filed under Sci-fi only. */
    private Book dune;
    /** Filed under Space and also directly under Fiction. */
    private Book solaris;
    /** Filed under Space, then trashed. */
    private Book foundation;

    /** Fiction › Sci-fi › Space, and three books filed in it. */
    @BeforeEach
    void setUp() throws IOException, ShelfWiseException {
        session = new Session(new SettingsFile(folder.resolve("settings.json")));
        session.getSettings().setDataFolder(folder.resolve("data"));
        session.openCollection(false);
        manager = session.getManager();

        fiction = new Category("Fiction");
        sciFi = new Category("Sci-fi");
        space = new Category("Space");
        manager.addCategory(fiction);
        manager.addCategory(sciFi, fiction);
        manager.addCategory(space, sciFi);

        dune = manager.addItem("Dune");
        solaris = manager.addItem("Solaris");
        foundation = manager.addItem("Foundation");
        manager.fileUnder(dune, sciFi);
        manager.fileUnder(solaris, space);
        manager.fileUnder(solaris, fiction);
        manager.fileUnder(foundation, space);
        manager.moveToTrash(foundation.getId());
    }

    /** A shell that does nothing: the adapters only ask it to save after create. */
    private static Shell quietShell() {
        return new Shell() {
            @Override public void toast(String message) { }
            @Override public void toast(String message, String action, Runnable onAction) { }
            @Override public void toast(String message, String action, Runnable onAction,
                                        String action2, Runnable onAction2) { }
            @Override public boolean saveChanges() { return true; }
            @Override public void collectionReplaced() { }
            @Override public PageController show(PageId page) { return null; }
            @Override public PageController show(PageId page, PageId lit) { return null; }
            @Override public Window getWindow() { return null; }
            @Override public boolean isDark() { return true; }
            @Override public void setDark(boolean dark) { }
        };
    }

    /** A book's filings as a set: Undo may put them back in a different order. */
    private static Set<String> filings(Book book) {
        return new HashSet<>(book.getCategoryIds());
    }

    private static void runAll(List<Runnable> steps) {
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).run();
        }
    }

    @Test
    @DisplayName("deleting a category with its books moved to the parent, then Undo, restores every filing")
    void moveUpThenUndoRestoresFilings() throws ShelfWiseException {
        Set<String> duneBefore = filings(dune);
        Set<String> solarisBefore = filings(solaris);
        Set<String> foundationBefore = filings(foundation);
        CategoryTree tree = new CategoryTree(session, quietShell());

        List<Runnable> undo = tree.release(sciFi, fiction);
        tree.delete(sciFi);
        // Every book, the trashed one too, now sits directly under Fiction.
        assertEquals(1, filings(dune).size());
        assertEquals(1, filings(solaris).size());
        assertEquals(1, filings(foundation).size());
        assertNull(manager.findCategoryById(space.getId()));

        tree.restore(sciFi, fiction);
        runAll(undo);
        assertEquals(duneBefore, filings(dune));
        // Solaris was under Fiction before the delete; Undo must not take that away.
        assertEquals(solarisBefore, filings(solaris));
        assertEquals(foundationBefore, filings(foundation));
        assertSame(space, manager.findCategoryById(space.getId()));
    }

    @Test
    @DisplayName("deleting a category with its books unfiled, then Undo, restores every filing")
    void removeThenUndoRestoresFilings() throws ShelfWiseException {
        Set<String> duneBefore = filings(dune);
        Set<String> solarisBefore = filings(solaris);
        Set<String> foundationBefore = filings(foundation);
        CategoryTree tree = new CategoryTree(session, quietShell());

        List<Runnable> undo = tree.release(sciFi, null);
        tree.delete(sciFi);
        assertEquals(0, filings(dune).size());
        assertEquals(1, filings(solaris).size());
        assertEquals(0, filings(foundation).size());

        tree.restore(sciFi, fiction);
        runAll(undo);
        assertEquals(duneBefore, filings(dune));
        assertEquals(solarisBefore, filings(solaris));
        assertEquals(foundationBefore, filings(foundation));
    }

    @Test
    @DisplayName("deleting a location with its copies unshelved, then Undo, puts every copy back")
    void unshelveThenUndoRestoresShelves() throws ShelfWiseException {
        Location room = new Location("Study");
        Location shelf = new Location("Shelf 1");
        manager.addLocation(room);
        manager.addLocation(shelf, room);
        Copy duneCopy = dune.getCopies().get(0);
        Copy trashedCopy = foundation.getCopies().get(0);
        manager.setLocation(duneCopy, shelf);
        manager.setLocation(trashedCopy, room);
        LocationTree tree = new LocationTree(session, quietShell());

        List<Runnable> undo = tree.release(room, null);
        tree.delete(room);
        assertNull(duneCopy.getLocationId());
        assertNull(trashedCopy.getLocationId());

        tree.restore(room, null);
        runAll(undo);
        assertEquals(shelf.getId(), duneCopy.getLocationId());
        assertEquals(room.getId(), trashedCopy.getLocationId());
    }

    @Test
    @DisplayName("renaming a category to a sibling's name is refused")
    void renameClashIsRefused() {
        final CategoryTree tree = new CategoryTree(session, quietShell());
        Category fantasy = new Category("Fantasy");
        manager.addCategory(fantasy, fiction);
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                tree.rename(sciFi, "fantasy");
            }
        });
    }
}
