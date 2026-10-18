package com.shelfwise.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Database}, run against real SQLite files in a temporary folder. */
class DatabaseTest {

    /** A fresh folder per test, deleted afterwards by JUnit. */
    @TempDir
    Path folder;

    /**
     * @param database the database to inspect
     * @param type     "table" or "view"
     * @return the names of every object of that type
     */
    private static List<String> namesOf(Database database, String type) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = database.openConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = '" + type + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Test
    @DisplayName("initialising creates every table and view in the schema")
    void createsSchema() throws Exception {
        Database db = new Database(folder.resolve("shelfwise.db"));
        db.initialiseSchema();

        List<String> tables = namesOf(db, "table");
        String[] expected = {"location", "category", "series", "item", "book", "copy",
                "contributor", "book_contributor", "item_category", "reading_session",
                "wishlist", "wishlist_entry", "custom_field", "app_setting"};
        for (int i = 0; i < expected.length; i++) {
            assertTrue(tables.contains(expected[i]), "missing table " + expected[i]);
        }
        assertEquals(3, namesOf(db, "view").size());
    }

    @Test
    @DisplayName("initialising twice is harmless and creates missing folders")
    void idempotentAndCreatesFolder() throws Exception {
        Database db = new Database(folder.resolve("nested").resolve("shelfwise.db"));
        db.initialiseSchema();
        db.initialiseSchema();
        assertTrue(Files.exists(db.getFile()));
    }

    @Test
    @DisplayName("every connection has foreign keys switched on")
    void foreignKeysOn() throws Exception {
        // SQLite's default is OFF; a missing pragma would make every
        // RESTRICT rule silently do nothing.
        Database db = new Database(folder.resolve("shelfwise.db"));
        db.initialiseSchema();
        try (Connection c = db.openConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("deleting a category that has a sub-category is refused by the database")
    void restrictIsEnforced() throws Exception {
        // Proves the pragma is really in effect, not just reported.
        final Database db = new Database(folder.resolve("shelfwise.db"));
        db.initialiseSchema();
        try (Connection c = db.openConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO category (id, name, parent_id) VALUES ('p', 'Fiction', NULL)");
            s.execute("INSERT INTO category (id, name, parent_id) VALUES ('c', 'Sci-fi', 'p')");
        }

        assertThrows(SQLException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                try (Connection c = db.openConnection(); Statement s = c.createStatement()) {
                    s.execute("DELETE FROM category WHERE id = 'p'");
                }
            }
        });
    }

    @Test
    @DisplayName("semicolons inside schema comments do not split statements")
    void commentsStripped() throws Exception {
        List<String> statements = Database.readSchemaStatements();
        for (int i = 0; i < statements.size(); i++) {
            String first = statements.get(i).split("\\s+")[0];
            assertTrue(first.equals("CREATE") || first.equals("PRAGMA"),
                    "unexpected statement start: " + statements.get(i));
        }
        assertEquals(30, statements.size());
    }

    @Test
    @DisplayName("a null file path is rejected")
    void nullPathRejected() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Database(null);
            }
        });
    }
}
