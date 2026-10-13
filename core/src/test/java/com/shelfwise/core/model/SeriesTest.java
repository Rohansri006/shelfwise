package com.shelfwise.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Series}. */
class SeriesTest {

    @Test
    @DisplayName("a new series has an unknown length and its own identity")
    void newSeriesDefaults() {
        Series s = new Series("Discworld");
        assertEquals("Discworld", s.getName());
        assertEquals(0, s.getTotalBooks());
        assertFalse(s.isTotalKnown());
        assertNotNull(s.getId());
    }

    @Test
    @DisplayName("the two-argument constructor records the total")
    void constructorWithTotal() {
        Series s = new Series("The Expanse", 9);
        assertEquals(9, s.getTotalBooks());
        assertTrue(s.isTotalKnown());
    }

    @Test
    @DisplayName("the storage constructor keeps the id it is given")
    void storageConstructorKeepsId() {
        Series s = new Series("series-1", "Discworld", 41);
        assertEquals("series-1", s.getId());
        assertEquals(41, s.getTotalBooks());
    }

    @Test
    @DisplayName("blank and null names are rejected")
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Series("   ");
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Series(null);
            }
        });
    }

    @Test
    @DisplayName("surrounding spaces are trimmed from the name")
    void nameTrimmed() {
        // Otherwise "Discworld " and "Discworld" would slip past the
        // database's UNIQUE constraint as two different series.
        assertEquals("Discworld", new Series("  Discworld ").getName());
    }

    @Test
    @DisplayName("a negative total is rejected, naming the value")
    void negativeTotalRejected() {
        final Series s = new Series("Discworld");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        s.setTotalBooks(-3);
                    }
                });
        assertTrue(e.getMessage().contains("-3"), e.getMessage());
    }

    @Test
    @DisplayName("a total of zero is accepted and means unknown")
    void zeroMeansUnknown() {
        Series s = new Series("The Expanse", 9);
        s.setTotalBooks(0);
        assertEquals(0, s.getTotalBooks());
        assertFalse(s.isTotalKnown());
    }

    @Test
    @DisplayName("equality uses the id alone, so it survives a rename")
    void equalityById() {
        Series original = new Series("series-1", "Discworld", 41);
        Series renamed = new Series("series-1", "Terry Pratchett's Discworld", 41);
        assertEquals(original, renamed);
        assertEquals(original.hashCode(), renamed.hashCode());

        // Two separately created series that happen to share a name are
        // still different objects to the model.
        assertNotEquals(new Series("Discworld"), new Series("Discworld"));
    }

    @Test
    @DisplayName("toString shows the name")
    void toStringIsName() {
        assertEquals("Discworld", new Series("Discworld").toString());
    }

    @Test
    @DisplayName("a book references a series by id, keeping the series ignorant of its books")
    void bookReferencesSeriesById() {
        // Deliberately one-directional, like Copy and Location: the book
        // knows its series, the series knows nothing about books.
        Series series = new Series("Discworld", 41);
        Book book = new Book("Guards! Guards!");

        book.setSeriesId(series.getId());
        book.setSeriesPosition(8);

        assertEquals(series.getId(), book.getSeriesId());
        assertEquals(8, book.getSeriesPosition());
    }
}
