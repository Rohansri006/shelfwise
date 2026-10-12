package com.shelfwise.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A physical place a copy of a book can be kept, nestable to any depth - a
 * shelf, a row within it, a box in the loft.
 * <p>
 * <strong>Locations hold copies, not books.</strong> That distinction is the
 * whole reason {@link Copy} exists: a book is an abstract work and is not
 * anywhere, while each physical copy sits in exactly one place. Two copies of
 * the same title can therefore live on different shelves.
 * <p>
 * The structure deliberately imposes no fixed levels. "Shelf then row" is
 * offered as a starting template by
 * {@link #createShelfWithRows(String, int)}, but someone who keeps books in
 * labelled crates, or in one pile, is not forced into a shape that does not
 * match their room.
 * <p>
 * Storage works exactly as for {@link Category}: only {@link #getParentId()}
 * is persisted, becoming a self-referencing foreign key, while the
 * {@code parent} and {@code children} object references live only in memory
 * and are wired up after loading.
 * <p>
 * Deleting a location that still holds copies is refused, but that rule is
 * not enforced here - this class knows nothing about copies. It lives in the
 * service layer and in the database's {@code ON DELETE RESTRICT} on the
 * copy's location foreign key, which raises
 * {@code LocationNotEmptyException}. Keeping the tree node ignorant of copies
 * is what stops the model becoming a web of mutual references.
 */
public class Location {

    /** Permanent internal identity. */
    private final String id;

    /** Display name. Required, and unique among its siblings. */
    private String name;

    /** Id of the parent, or null for a top-level location. The only stored link. */
    private String parentId;

    /** In-memory parent reference, wired after loading. Not persisted. */
    private Location parent;

    /** In-memory children, wired after loading. Not persisted. */
    private final List<Location> children;

    /**
     * Creates a top-level location with a fresh identity.
     *
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Location(String name) {
        this(UUID.randomUUID().toString(), name);
    }

    /**
     * Recreates a location that already exists, keeping its identity. Used by
     * the storage layer when loading.
     *
     * @param id   the existing identity
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Location(String id, String name) {
        this.id = id;
        this.children = new ArrayList<>();
        setName(name);
    }

    /**
     * Builds a shelf with numbered rows beneath it - the suggested starting
     * shape for a new collection.
     * <p>
     * A static factory rather than a constructor because it returns a whole
     * small tree rather than configuring one object, and because
     * {@code createShelfWithRows("Study", 5)} says what it does in a way a
     * two-argument constructor could not.
     *
     * @param shelfName name for the shelf itself
     * @param rowCount  how many rows to create beneath it; may be 0
     * @return the shelf, with its rows already attached
     * @throws IllegalArgumentException if the name is blank or rowCount is
     *                                  negative
     */
    public static Location createShelfWithRows(String shelfName, int rowCount) {
        if (rowCount < 0) {
            throw new IllegalArgumentException(
                    "Row count cannot be negative, but was " + rowCount + ".");
        }
        Location shelf = new Location(shelfName);
        for (int i = 1; i <= rowCount; i++) {
            shelf.addChild(new Location("Row " + i));
        }
        return shelf;
    }

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return the display name */
    public String getName() {
        return name;
    }

    /**
     * @param name the display name; must not be null or blank
     * @throws IllegalArgumentException if null or blank
     */
    public void setName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Location name must not be blank.");
        }
        this.name = name.trim();
    }

    /** @return the parent's id, or null if this is a top-level location */
    public String getParentId() {
        return parentId;
    }

    /** @return the in-memory parent, or null if this is top-level */
    public Location getParent() {
        return parent;
    }

    /** @return true if this location sits at the top of the tree */
    public boolean isRoot() {
        return parentId == null;
    }

    /** @return an unmodifiable view of the direct children */
    public List<Location> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** @return true if this location has no sub-locations */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * Attaches a sub-location, setting both halves of the link and detaching
     * it from any previous parent.
     *
     * @param child the location to attach; must not be null
     * @throws IllegalArgumentException if the child is null, is this location
     *                                  itself, would create a cycle, or
     *                                  duplicates an existing sibling's name
     */
    public void addChild(Location child) {
        if (child == null) {
            throw new IllegalArgumentException("Child location must not be null.");
        }
        if (child == this || child.getId().equals(this.id)) {
            throw new IllegalArgumentException(
                    "A location cannot be inside itself: \"" + name + "\".");
        }
        // Without this, moving a shelf into one of its own rows would detach
        // that whole branch from the tree and lose every copy filed under it.
        if (child.isAncestorOf(this)) {
            throw new IllegalArgumentException(
                    "Cannot move \"" + child.getName() + "\" inside \"" + name
                            + "\": it already contains it.");
        }
        if (findChildByName(child.getName()) != null) {
            throw new IllegalArgumentException(
                    "\"" + name + "\" already contains a location called \""
                            + child.getName() + "\".");
        }

        if (child.parent != null) {
            child.parent.children.remove(child);
        }

        children.add(child);
        child.parent = this;
        child.parentId = this.id;
    }

    /**
     * Detaches a direct sub-location, making it top-level.
     *
     * @param child the child to detach
     * @return true if it was a direct child of this location
     */
    public boolean removeChild(Location child) {
        boolean removed = children.remove(child);
        if (removed) {
            child.parent = null;
            child.parentId = null;
        }
        return removed;
    }

    /**
     * Finds a direct sub-location by name, ignoring case.
     *
     * @param childName the name to look for
     * @return the matching child, or null if there is none
     */
    public Location findChildByName(String childName) {
        if (childName == null) {
            return null;
        }
        String wanted = childName.trim();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).getName().equalsIgnoreCase(wanted)) {
                return children.get(i);
            }
        }
        return null;
    }

    /**
     * Whether this location contains another, at any depth.
     *
     * @param other the location to test
     * @return true if {@code other} is somewhere inside this one
     */
    public boolean isAncestorOf(Location other) {
        if (other == null) {
            return false;
        }
        Location walker = other.parent;
        while (walker != null) {
            if (walker == this || walker.getId().equals(this.id)) {
                return true;
            }
            walker = walker.parent;
        }
        return false;
    }

    /**
     * This location and everything inside it, at any depth.
     * <p>
     * What "show me everything on this shelf" needs: selecting a shelf must
     * include the copies filed in its individual rows. It is also how the
     * not-empty check finds copies anywhere beneath a location before
     * allowing a delete.
     *
     * @return this location followed by all descendants
     */
    public List<Location> getSubtree() {
        List<Location> collected = new ArrayList<>();
        collectSubtree(collected);
        return collected;
    }

    /**
     * Adds this location and its descendants to the given list.
     *
     * @param collected the list being filled
     */
    private void collectSubtree(List<Location> collected) {
        collected.add(this);
        for (int i = 0; i < children.size(); i++) {
            children.get(i).collectSubtree(collected);
        }
    }

    /**
     * How deep this location sits, counting a top-level location as zero.
     *
     * @return the number of locations above this one
     */
    public int getDepth() {
        int depth = 0;
        Location walker = parent;
        while (walker != null) {
            depth++;
            walker = walker.parent;
        }
        return depth;
    }

    /**
     * The full path from the top, for display.
     *
     * @return names joined by " &gt; ", e.g. "Study shelf &gt; Row 2"
     */
    public String getPath() {
        List<String> names = new ArrayList<>();
        Location walker = this;
        while (walker != null) {
            names.add(walker.getName());
            walker = walker.parent;
        }

        StringBuilder path = new StringBuilder();
        for (int i = names.size() - 1; i >= 0; i--) {
            path.append(names.get(i));
            if (i > 0) {
                path.append(" > ");
            }
        }
        return path.toString();
    }

    /**
     * Identity is the id alone, so renaming a shelf keeps every copy on it.
     *
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Location)) {
            return false;
        }
        return id.equals(((Location) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return the full path, which identifies a shelf more usefully than its name */
    @Override
    public String toString() {
        return getPath();
    }
}
