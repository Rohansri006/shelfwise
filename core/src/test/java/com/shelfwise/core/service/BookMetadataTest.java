package com.shelfwise.core.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link BookMetadata}. */
class BookMetadataTest {

    @Test
    @DisplayName("a new result holds only its title, with numbers unknown")
    void defaults() {
        BookMetadata m = new BookMetadata("  Dune ");
        assertEquals("Dune", m.getTitle());
        assertEquals(0, m.getYear());
        assertEquals(0, m.getPages());
        assertTrue(m.getAuthors().isEmpty());
    }

    @Test
    @DisplayName("a blank title is rejected")
    void blankTitleRejected() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new BookMetadata(" ");
            }
        });
    }

    @Test
    @DisplayName("authors keep their order and blank names are skipped")
    void authors() {
        // Catalogues do return empty author entries; one should not break a
        // lookup.
        BookMetadata m = new BookMetadata("Good Omens");
        m.addAuthor("Terry Pratchett");
        m.addAuthor("");
        m.addAuthor(null);
        m.addAuthor(" Neil Gaiman ");

        assertEquals(2, m.getAuthors().size());
        assertEquals("Neil Gaiman", m.getAuthors().get(1));
        assertEquals("Good Omens by Terry Pratchett", m.toString());
    }

    @Test
    @DisplayName("negative year and page count are rejected, naming the value")
    void negativeNumbersRejected() {
        final BookMetadata m = new BookMetadata("Dune");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        m.setPages(-5);
                    }
                });
        assertTrue(e.getMessage().contains("-5"), e.getMessage());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                m.setYear(-1);
            }
        });
    }

    @Test
    @DisplayName("authors getter is unmodifiable")
    void authorsUnmodifiable() {
        final BookMetadata m = new BookMetadata("Dune");
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                m.getAuthors().add("Sneaky");
            }
        });
    }
}
