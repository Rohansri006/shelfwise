package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the user-visible wording of Bulk scan. Both messages are built
 * by static methods from plain values, so they can be checked without
 * starting JavaFX or a camera.
 */
class BulkScanControllerTest {

    /** @return a queue row for an ISBN, with a looked-up title or none */
    private static BulkScanController.Row row(String isbn, String title) {
        BulkScanController.Row row = new BulkScanController.Row();
        row.isbn = isbn;
        row.draft = title == null ? null : new Book(title);
        return row;
    }

    @Test
    @DisplayName("removing one row names its book")
    void removeOneNamesTheBook() {
        List<BulkScanController.Row> one = new ArrayList<>();
        one.add(row("9780143031055", "Glimpses of World History"));
        assertEquals("Remove “Glimpses of World History” from the queue?", BulkScanController.removeMessage(one));
    }

    @Test
    @DisplayName("removing one row with no title yet names its ISBN")
    void removeOneWithoutTitleNamesTheIsbn() {
        // A row still looking up, or not found, has no title to show.
        List<BulkScanController.Row> one = new ArrayList<>();
        one.add(row("9788183071000", null));
        assertEquals("Remove “9788183071000” from the queue?", BulkScanController.removeMessage(one));
    }

    @Test
    @DisplayName("removing several rows counts them")
    void removeSeveralCountsThem() {
        List<BulkScanController.Row> two = new ArrayList<>();
        two.add(row("9780143031055", "Glimpses of World History"));
        two.add(row("9788183071000", null));
        assertEquals("Remove 2 rows from the queue?", BulkScanController.removeMessage(two));
    }

    @Test
    @DisplayName("the Confirm all toast uses singular words for one of each")
    void confirmMessageSingular() {
        assertEquals("1 book added, 1 copy added, 1 restored", BulkScanController.confirmMessage(1, 1, 1));
    }

    @Test
    @DisplayName("the Confirm all toast uses plural words for several")
    void confirmMessagePlural() {
        assertEquals("3 books added, 2 copies added, 4 restored", BulkScanController.confirmMessage(3, 2, 4));
    }

    @Test
    @DisplayName("the Confirm all toast leaves out what did not happen")
    void confirmMessageSkipsZeroes() {
        assertEquals("2 copies added", BulkScanController.confirmMessage(0, 2, 0));
        // Nothing done means no toast at all.
        assertEquals("", BulkScanController.confirmMessage(0, 0, 0));
    }
}
