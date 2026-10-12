package com.shelfwise.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@link Location} tree. */
class LocationTest {

    @Test
    @DisplayName("a new location is top-level with nothing inside it")
    void newLocationIsRoot() {
        Location l = new Location("Study shelf");
        assertTrue(l.isRoot());
        assertTrue(l.isLeaf());
        assertNull(l.getParentId());
        assertEquals(0, l.getDepth());
    }

    @Test
    @DisplayName("blank names are rejected")
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Location("  ");
            }
        });
    }

    @Test
    @DisplayName("the shelf-and-rows template builds the suggested starting shape")
    void shelfTemplate() {
        Location shelf = Location.createShelfWithRows("Study shelf", 3);

        assertEquals("Study shelf", shelf.getName());
        assertEquals(3, shelf.getChildren().size());
        assertEquals("Row 1", shelf.getChildren().get(0).getName());
        assertEquals("Row 3", shelf.getChildren().get(2).getName());
        assertEquals("Study shelf > Row 2", shelf.getChildren().get(1).getPath());
        // Four nodes in total: the shelf plus its three rows.
        assertEquals(4, shelf.getSubtree().size());
    }

    @Test
    @DisplayName("the template accepts zero rows but not a negative count")
    void shelfTemplateEdgeCases() {
        // Zero is meaningful: a shelf someone has not subdivided.
        assertEquals(0, Location.createShelfWithRows("Box in the loft", 0)
                .getChildren().size());

        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                Location.createShelfWithRows("Shelf", -1);
            }
        });
    }

    @Test
    @DisplayName("depth is unlimited, so a room-shelf-row-box structure works")
    void arbitraryDepth() {
        // The spec requires no fixed number of levels, so this confirms four
        // deep is no different from two.
        Location room = new Location("Study");
        Location shelf = new Location("Bookcase");
        Location row = new Location("Row 2");
        Location box = new Location("Box A");

        room.addChild(shelf);
        shelf.addChild(row);
        row.addChild(box);

        assertEquals(3, box.getDepth());
        assertEquals("Study > Bookcase > Row 2 > Box A", box.getPath());
        assertEquals(4, room.getSubtree().size());
    }

    @Test
    @DisplayName("addChild sets both directions of the link")
    void addChildSetsBothDirections() {
        Location shelf = new Location("Study shelf");
        Location row = new Location("Row 1");
        shelf.addChild(row);

        assertSame(shelf, row.getParent());
        assertEquals(shelf.getId(), row.getParentId());
        assertFalse(row.isRoot());
    }

    @Test
    @DisplayName("sibling names must be unique, case-insensitively")
    void siblingNamesUnique() {
        final Location shelf = Location.createShelfWithRows("Study shelf", 2);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        shelf.addChild(new Location("row 1"));
                    }
                });
        assertTrue(e.getMessage().contains("already contains"), e.getMessage());
    }

    @Test
    @DisplayName("the same name is allowed under different parents")
    void sameNameUnderDifferentParents() {
        // Essential here: every shelf has a "Row 1". A globally unique rule
        // would make the obvious naming scheme impossible.
        Location studyShelf = Location.createShelfWithRows("Study shelf", 1);
        Location bedroomShelf = Location.createShelfWithRows("Bedroom shelf", 1);

        assertEquals("Study shelf > Row 1", studyShelf.getChildren().get(0).getPath());
        assertEquals("Bedroom shelf > Row 1", bedroomShelf.getChildren().get(0).getPath());
    }

    @Test
    @DisplayName("a location cannot be moved inside its own sub-location")
    void cannotCreateCycle() {
        // Dragging a shelf into one of its own rows. Unchecked, the branch
        // detaches from the tree and every copy filed under it is orphaned.
        final Location shelf = Location.createShelfWithRows("Study shelf", 2);
        final Location row = shelf.getChildren().get(0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        row.addChild(shelf);
                    }
                });
        assertTrue(e.getMessage().contains("already contains"), e.getMessage());
    }

    @Test
    @DisplayName("a location cannot be inside itself")
    void cannotContainItself() {
        final Location shelf = new Location("Study shelf");
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                shelf.addChild(shelf);
            }
        });
    }

    @Test
    @DisplayName("moving a location detaches it from its previous parent")
    void moveDetachesFromOldParent() {
        Location oldShelf = new Location("Old shelf");
        Location newShelf = new Location("New shelf");
        Location row = new Location("Row 1");

        oldShelf.addChild(row);
        newShelf.addChild(row);

        assertEquals(0, oldShelf.getChildren().size());
        assertEquals(1, newShelf.getChildren().size());
        assertEquals(newShelf.getId(), row.getParentId());
    }

    @Test
    @DisplayName("removeChild promotes the sub-location and keeps its contents")
    void removeChildPromotes() {
        Location shelf = Location.createShelfWithRows("Study shelf", 2);
        Location row = shelf.getChildren().get(0);
        row.addChild(new Location("Box A"));

        assertTrue(shelf.removeChild(row));
        assertTrue(row.isRoot());
        // Box A travels with its row rather than being lost.
        assertEquals(2, row.getSubtree().size());
    }

    @Test
    @DisplayName("getSubtree is what the not-empty check relies on")
    void subtreeSupportsNotEmptyCheck() {
        // Deleting a shelf must be refused if a copy sits in any row beneath
        // it, so the rule needs every descendant, not just direct children.
        Location shelf = Location.createShelfWithRows("Study shelf", 3);
        List<Location> all = shelf.getSubtree();

        assertEquals(4, all.size());
        assertSame(shelf, all.get(0));
    }

    @Test
    @DisplayName("children getter is unmodifiable")
    void childrenUnmodifiable() {
        final Location shelf = new Location("Study shelf");
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                shelf.getChildren().add(new Location("Sneaky"));
            }
        });
    }

    @Test
    @DisplayName("a copy references a location by id, keeping the tree ignorant of copies")
    void copyReferencesLocationById() {
        // Deliberately one-directional: Copy knows its location, Location
        // knows nothing about copies. Otherwise the two would point at each
        // other and neither could be loaded without the other.
        Location row = Location.createShelfWithRows("Study shelf", 1)
                .getChildren().get(0);

        Copy copy = new Copy(Format.HARDCOVER, Condition.NEW);
        assertTrue(copy.isUnshelved());

        copy.setLocationId(row.getId());
        assertFalse(copy.isUnshelved());
        assertEquals(row.getId(), copy.getLocationId());
    }
}
