package com.shelfwise.core.storage;

import com.shelfwise.core.model.Priority;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip tests for {@link SeriesDao}, {@link WishlistDao} and {@link SettingDao}. */
class SimpleDaoTest {

    @TempDir
    Path folder;

    private Database db;

    @BeforeEach
    void createDatabase() throws Exception {
        db = new Database(folder.resolve("shelfwise.db"));
        db.initialiseSchema();
    }

    @Test
    @DisplayName("series round-trip with their totals, and deleteAll empties the table")
    void seriesRoundTrip() throws Exception {
        List<Series> series = new ArrayList<>();
        series.add(new Series("s1", "Discworld", 41));
        series.add(new Series("s2", "The Expanse", 0));

        SeriesDao dao = new SeriesDao();
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, series);
            List<Series> loaded = dao.loadAll(c);
            assertEquals(2, loaded.size());
            assertTrue(loaded.contains(new Series("s1", "Anything", 0)));
            dao.deleteAll(c);
            assertTrue(dao.loadAll(c).isEmpty());
        }
    }

    @Test
    @DisplayName("wishlists come back holding their own entries, every field and the order intact")
    void wishlistRoundTrip() throws Exception {
        Wishlist birthday = new Wishlist("w1", "Birthday ideas");
        WishlistEntry dune = new WishlistEntry("e1", "Dune");
        dune.setIsbn("978-0-14-303105-5");
        dune.setAuthor("Frank Herbert");
        dune.setPriority(Priority.HIGH);
        dune.setExpectedPrice(599.5);
        dune.setStore("Crossword");
        dune.setRare(true);
        birthday.addEntry(dune);
        birthday.addEntry(new WishlistEntry("e2", "Neuromancer"));
        Wishlist rare = new Wishlist("w2", "Out of print");
        rare.addEntry(new WishlistEntry("e3", "Gormenghast"));

        List<Wishlist> lists = new ArrayList<>();
        lists.add(birthday);
        lists.add(rare);

        WishlistDao dao = new WishlistDao();
        List<Wishlist> loaded;
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, lists);
            loaded = dao.loadAll(c);
        }

        assertEquals("Birthday ideas", loaded.get(0).getName());
        assertEquals(2, loaded.get(0).getEntryCount());
        assertEquals(1, loaded.get(1).getEntryCount());

        WishlistEntry back = loaded.get(0).getEntries().get(0);
        assertEquals("e1", back.getId());
        assertEquals("9780143031055", back.getIsbn());
        assertEquals("Frank Herbert", back.getAuthor());
        assertEquals(Priority.HIGH, back.getPriority());
        assertEquals(599.5, back.getExpectedPrice());
        assertEquals("Crossword", back.getStore());
        assertTrue(back.isRare());
        assertEquals("Neuromancer", loaded.get(0).getEntries().get(1).getTitle());
    }

    @Test
    @DisplayName("deleting the wishlists deletes their entries by cascade")
    void wishlistCascade() throws Exception {
        Wishlist w = new Wishlist("w1", "Birthday ideas");
        w.addEntry(new WishlistEntry("e1", "Dune"));
        List<Wishlist> lists = new ArrayList<>();
        lists.add(w);

        WishlistDao dao = new WishlistDao();
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, lists);
            dao.deleteAll(c);
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM wishlist_entry")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("a setting is null until set, and a second put replaces the first")
    void settings() throws Exception {
        SettingDao dao = new SettingDao();
        try (Connection c = db.openConnection()) {
            assertNull(dao.get(c, SettingDao.CURRENCY));
            dao.put(c, SettingDao.CURRENCY, "INR");
            dao.put(c, SettingDao.CURRENCY, "USD");
            assertEquals("USD", dao.get(c, SettingDao.CURRENCY));
        }
    }
}
