package com.shelfwise.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the model enums.
 * <p>
 * These enums are not bare constant lists: each carries a display label, and
 * two of them have behaviour that depends on the order their constants are
 * declared in. That ordering is easy to break accidentally while editing, so
 * it is pinned down here.
 */
class ModelEnumsTest {

    @Test
    @DisplayName("every constant has a human-readable label, never the constant name")
    void labelsAreHumanReadable() {
        // LIKE_NEW is the case that matters: the constant name must never
        // reach a user, so the label has to differ from name().
        assertEquals("Like new", Condition.LIKE_NEW.getLabel());
        assertEquals("E-book", Format.EBOOK.getLabel());
        assertEquals("Illustrator", ContributorRole.ILLUSTRATOR.getLabel());
        assertEquals("Medium", Priority.MEDIUM.getLabel());
        assertEquals("Unread", ReadingStatus.UNREAD.getLabel());
    }

    @Test
    @DisplayName("toString returns the label, so enums drop straight into UI text")
    void toStringUsesLabel() {
        // This is what lets a combo box or a string template show "Like new"
        // without every call site remembering to ask for getLabel().
        assertEquals("Like new", Condition.LIKE_NEW.toString());
        assertEquals("E-book", Format.EBOOK.toString());
        assertEquals("High", Priority.HIGH.toString());
    }

    @Test
    @DisplayName("Condition is declared best to worst, so comparison works")
    void conditionOrdering() {
        assertTrue(Condition.NEW.isBetterThan(Condition.GOOD));
        assertTrue(Condition.GOOD.isBetterThan(Condition.POOR));
        assertFalse(Condition.POOR.isBetterThan(Condition.FAIR));
        // Not better than itself.
        assertFalse(Condition.GOOD.isBetterThan(Condition.GOOD));
    }

    @Test
    @DisplayName("Priority is declared low to high, so natural ordering sorts sensibly")
    void priorityOrdering() {
        // compareTo on an enum uses declaration order, which is why the
        // constants are not alphabetical.
        assertTrue(Priority.LOW.compareTo(Priority.HIGH) < 0);
        assertTrue(Priority.HIGH.compareTo(Priority.MEDIUM) > 0);
    }

    @Test
    @DisplayName("hasBeenStarted distinguishes untouched books from abandoned ones")
    void hasBeenStarted() {
        // ABANDONED must count as started: "gave up on it" is information the
        // user chose to record, and collapsing it into UNREAD discards that.
        assertFalse(ReadingStatus.UNREAD.hasBeenStarted());
        assertTrue(ReadingStatus.READING.hasBeenStarted());
        assertTrue(ReadingStatus.FINISHED.hasBeenStarted());
        assertTrue(ReadingStatus.ABANDONED.hasBeenStarted());
    }

    @Test
    @DisplayName("constant sets match the schema's CHECK constraints")
    void constantSetsMatchSchema() {
        // schema.sql restricts these columns with CHECK (... IN (...)), listing
        // the same names. If a constant is added here without updating the
        // schema, inserts fail at runtime - so the counts are pinned.
        assertEquals(4, ContributorRole.values().length);
        assertEquals(5, Format.values().length);
        assertEquals(5, Condition.values().length);
        assertEquals(3, Priority.values().length);
        assertEquals(4, ReadingStatus.values().length);
    }
}
