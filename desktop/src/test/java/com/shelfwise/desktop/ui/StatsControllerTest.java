package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.service.CollectionManager;
import com.shelfwise.core.storage.SettingsFile;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for the Stats page's counting (E128-E141). The counting methods
 * touch no controls, so a {@link StatsController} made with {@code new}
 * (no FXML, no JavaFX) can count a small collection built here by hand.
 */
class StatsControllerTest {

    @TempDir
    Path folder;

    private CollectionManager manager;
    private StatsController stats;

    /** A shell that does nothing: counting never reaches the window. */
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

    @BeforeEach
    void setUp() throws IOException {
        Session session = new Session(new SettingsFile(folder.resolve("settings.json")));
        session.getSettings().setDataFolder(folder.resolve("data"));
        session.openCollection(false);
        manager = session.getManager();
        stats = new StatsController();
        stats.setUp(quietShell(), session);
    }

    /** A Finished book with one session that ended on the given date. */
    private Book finished(String title, int pages, LocalDate ended) throws ShelfWiseException {
        Book book = manager.addItem(title);
        book.setPages(pages);
        ReadingSession session = new ReadingSession(ended.minusDays(10));
        session.setEndDate(ended);
        book.addReadingSession(session);
        book.setReadingStatus(ReadingStatus.FINISHED);
        return book;
    }

    @Test
    @DisplayName("the per-year range runs from the first finish to this year, quiet years as 0")
    void perYearIsContinuous() throws ShelfWiseException {
        int thisYear = LocalDate.now().getYear();
        finished("Old", 300, LocalDate.of(thisYear - 3, 5, 1));
        finished("New", 200, LocalDate.of(thisYear, 1, 15));

        TreeMap<Integer, int[]> years = stats.perYear();
        assertEquals(4, years.size());
        assertArrayEquals(new int[] {1, 300}, years.get(thisYear - 3));
        // E131: the gap years are there, as zeros.
        assertArrayEquals(new int[] {0, 0}, years.get(thisYear - 2));
        assertArrayEquals(new int[] {0, 0}, years.get(thisYear - 1));
        assertArrayEquals(new int[] {1, 200}, years.get(thisYear));
    }

    @Test
    @DisplayName("spend is grouped by format, and copies without a price are counted separately")
    void spendByFormat() throws ShelfWiseException {
        Book a = manager.addItem("A");
        a.getCopies().get(0).setFormat(Format.PAPERBACK);
        a.getCopies().get(0).setPricePaid(399);
        Book b = manager.addItem("B");
        b.getCopies().get(0).setFormat(Format.PAPERBACK);
        // B's copy has no price (0 means not recorded).
        Book c = manager.addItem("C");
        c.getCopies().get(0).setFormat(Format.HARDCOVER);
        c.getCopies().get(0).setPricePaid(1200);

        Map<Format, double[]> spend = stats.spendByFormat();
        assertArrayEquals(new double[] {2, 399}, spend.get(Format.PAPERBACK));
        assertArrayEquals(new double[] {1, 1200}, spend.get(Format.HARDCOVER));
        // A format with no copies is left out, not shown as 0.
        assertFalse(spend.containsKey(Format.EBOOK));
        assertEquals(1, stats.copiesWithoutPrice());
    }

    @Test
    @DisplayName("pages read count each Finished book once; unknown page counts are left out")
    void pagesCountFinishedOnce() throws ShelfWiseException {
        finished("Read", 400, LocalDate.now().minusDays(1));
        Book unread = manager.addItem("Unread");
        unread.setPages(100);
        manager.addItem("No pages");

        StatsController.Pages pages = stats.pages();
        assertEquals(500, pages.owned);
        assertEquals(400, pages.read);
        assertEquals(100, pages.left());
        assertEquals(1, pages.unknown);
        assertEquals("Read", pages.longest.getTitle());
        assertEquals("Unread", pages.shortest.getTitle());
    }

    @Test
    @DisplayName("a category's row rolls up its sub-categories, counting a book in both only once")
    void categoryRollsUpOnce() throws ShelfWiseException {
        Category fiction = new Category("Fiction");
        Category fantasy = new Category("Fantasy");
        manager.addCategory(fiction);
        manager.addCategory(fantasy, fiction);
        Book book = finished("Earthsea", 200, LocalDate.now().minusDays(1));
        manager.fileUnder(book, fiction);
        manager.fileUnder(book, fantasy);

        StatsController.CategoryRow row = stats.countCategory(fiction);
        assertEquals(1, row.books);
        assertEquals(200, row.owned);
        assertEquals("100%", row.percent());
    }
}
