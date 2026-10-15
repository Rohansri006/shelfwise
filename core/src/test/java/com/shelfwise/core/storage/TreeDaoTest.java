package com.shelfwise.core.storage;

import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip tests for {@link CategoryDao} and {@link LocationDao}. */
class TreeDaoTest {

    @TempDir
    Path folder;

    private Database db;

    @BeforeEach
    void createDatabase() throws Exception {
        db = new Database(folder.resolve("shelfwise.db"));
        db.initialiseSchema();
    }

    /**
     * @param all  every node loaded
     * @param path the full path to look for
     * @return the node with that path, or null
     */
    private static Category categoryAt(List<Category> all, String path) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getPath().equals(path)) {
                return all.get(i);
            }
        }
        return null;
    }

    @Test
    @DisplayName("a three-level category tree comes back with its links rebuilt")
    void categoryRoundTrip() throws Exception {
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        Category opera = new Category("Space opera");
        fiction.addChild(scifi);
        scifi.addChild(opera);
        // Deepest first, so insertAll has to reorder to satisfy the
        // foreign key.
        List<Category> nodes = new ArrayList<>();
        nodes.add(opera);
        nodes.add(scifi);
        nodes.add(fiction);

        CategoryDao dao = new CategoryDao();
        List<Category> loaded;
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, nodes);
            loaded = dao.loadAll(c);
        }

        assertEquals(3, loaded.size());
        Category loadedOpera = categoryAt(loaded, "Fiction > Science fiction > Space opera");
        assertNotNull(loadedOpera);
        assertEquals(opera.getId(), loadedOpera.getId());
        assertEquals(2, loadedOpera.getDepth());
        assertTrue(categoryAt(loaded, "Fiction").isRoot());
    }

    @Test
    @DisplayName("deleteAll empties a nested tree, which a plain DELETE cannot")
    void deleteAllNested() throws Exception {
        // Inserted parent first, so a plain DELETE would reach the parent
        // while its child still points at it and fail.
        Location shelf = Location.createShelfWithRows("Study shelf", 2);
        shelf.getChildren().get(0).addChild(new Location("Box A"));

        LocationDao dao = new LocationDao();
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, shelf.getSubtree());
            dao.deleteAll(c);
            assertTrue(dao.loadAll(c).isEmpty());
        }
    }

    @Test
    @DisplayName("locations with the same name under different parents round-trip")
    void sameRowNameOnTwoShelves() throws Exception {
        // Both shelves have a "Row 1"; the partial unique index on root names
        // must not reject them, because neither is a root.
        List<Location> nodes = new ArrayList<>();
        nodes.addAll(Location.createShelfWithRows("Study shelf", 1).getSubtree());
        nodes.addAll(Location.createShelfWithRows("Bedroom shelf", 1).getSubtree());

        LocationDao dao = new LocationDao();
        List<Location> loaded;
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, nodes);
            loaded = dao.loadAll(c);
        }

        int roots = 0;
        for (int i = 0; i < loaded.size(); i++) {
            if (loaded.get(i).isRoot()) {
                roots++;
                assertEquals(1, loaded.get(i).getChildren().size());
            }
        }
        assertEquals(2, roots);
        assertEquals(4, loaded.size());
    }

    @Test
    @DisplayName("an empty table loads as an empty list")
    void emptyLoad() throws Exception {
        try (Connection c = db.openConnection()) {
            assertTrue(new CategoryDao().loadAll(c).isEmpty());
            assertTrue(new LocationDao().loadAll(c).isEmpty());
        }
    }
}
