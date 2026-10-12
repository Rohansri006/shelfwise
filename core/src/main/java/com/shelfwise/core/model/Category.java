package com.shelfwise.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A user-created heading that books can be filed under, forming a tree of any
 * depth - for example Non-fiction, then Finance beneath it, then Financial
 * Markets beneath that.
 * <p>
 * <strong>Genres and tags are the same thing here.</strong> Earlier designs
 * had a genre tree and a separate flat tag list, which forced an unanswerable
 * question for every label: is "Homeopathy" a genre or a tag? Merging them
 * into one nestable structure removes the question entirely. A book may be
 * filed under any number of categories.
 * <p>
 * <strong>How the tree is stored versus how it is used.</strong> The only
 * persisted link is {@link #getParentId()}, a plain string. In the database
 * that becomes a self-referencing foreign key, which is what lets a flat
 * table hold a tree of unlimited depth. The {@code parent} and
 * {@code children} object references below exist purely in memory and are
 * wired up after loading; they are never written anywhere. Storing object
 * references instead would create a cycle - parent points to child, child
 * points back - which is exactly what makes naive serialisation either
 * recurse forever or duplicate whole subtrees.
 * <p>
 * Uniqueness is per-parent, not global: two children of one parent may not
 * share a name, but "Finance" may exist under both "Non-fiction" and
 * "Reference", because in a deep tree a globally unique rule becomes
 * frustrating fast.
 */
public class Category {

    /** Permanent internal identity. */
    private final String id;

    /** Display name. Required, and unique among its siblings. */
    private String name;

    /** Id of the parent, or null for a root category. The only stored link. */
    private String parentId;

    /**
     * In-memory parent reference, wired after loading. Not persisted - the
     * database stores {@link #parentId} instead.
     */
    private Category parent;

    /**
     * In-memory children, wired after loading. Not persisted: a child knows
     * its parent's id, and that single direction is enough to rebuild the
     * whole tree.
     */
    private final List<Category> children;

    /**
     * Creates a root category with a fresh identity.
     *
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Category(String name) {
        this(UUID.randomUUID().toString(), name);
    }

    /**
     * Recreates a category that already exists, keeping its identity. Used by
     * the storage layer when loading.
     *
     * @param id   the existing identity
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Category(String id, String name) {
        this.id = id;
        this.children = new ArrayList<>();
        setName(name);
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
            throw new IllegalArgumentException("Category name must not be blank.");
        }
        this.name = name.trim();
    }

    /** @return the parent's id, or null if this is a root category */
    public String getParentId() {
        return parentId;
    }

    /** @return the in-memory parent, or null if this is a root category */
    public Category getParent() {
        return parent;
    }

    /** @return true if this category sits at the top of the tree */
    public boolean isRoot() {
        return parentId == null;
    }

    /** @return an unmodifiable view of the direct children */
    public List<Category> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** @return true if this category has no children */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /**
     * Attaches a child beneath this category, setting both halves of the link.
     * <p>
     * Both directions are set here rather than left to the caller, because a
     * child whose {@code parentId} disagrees with the tree it is actually in
     * is a bug that only shows up after a save and reload.
     *
     * @param child the category to attach; must not be null
     * @throws IllegalArgumentException if the child is null, is this category
     *                                  itself, would create a cycle, or
     *                                  duplicates an existing sibling's name
     */
    public void addChild(Category child) {
        if (child == null) {
            throw new IllegalArgumentException("Child category must not be null.");
        }
        if (child == this || child.getId().equals(this.id)) {
            throw new IllegalArgumentException(
                    "A category cannot be its own parent: \"" + name + "\".");
        }
        // Walking up from this node, the prospective child must not appear.
        // Without this check, dragging a parent into its own descendant would
        // detach that whole branch from the tree and lose it.
        if (child.isAncestorOf(this)) {
            throw new IllegalArgumentException(
                    "Cannot move \"" + child.getName() + "\" beneath \"" + name
                            + "\": it is already above it in the tree.");
        }
        if (findChildByName(child.getName()) != null) {
            throw new IllegalArgumentException(
                    "\"" + name + "\" already has a sub-category called \""
                            + child.getName() + "\".");
        }

        // Detach from any previous parent first, so a move cannot leave the
        // category listed under both.
        if (child.parent != null) {
            child.parent.children.remove(child);
        }

        children.add(child);
        child.parent = this;
        child.parentId = this.id;
    }

    /**
     * Detaches a direct child, making it a root category.
     *
     * @param child the child to detach
     * @return true if it was a direct child of this category
     */
    public boolean removeChild(Category child) {
        boolean removed = children.remove(child);
        if (removed) {
            child.parent = null;
            child.parentId = null;
        }
        return removed;
    }

    /**
     * Finds a direct child by name, ignoring case.
     * <p>
     * Case-insensitive because the user who typed "Finance" yesterday and
     * "finance" today means the same category, and silently creating two
     * would be worse than refusing.
     *
     * @param childName the name to look for
     * @return the matching child, or null if there is none
     */
    public Category findChildByName(String childName) {
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
     * Whether this category lies somewhere above another in the tree.
     *
     * @param other the category to test
     * @return true if {@code other} is a descendant of this one
     */
    public boolean isAncestorOf(Category other) {
        if (other == null) {
            return false;
        }
        // Walk upwards from other; cheaper than searching the subtree below.
        Category walker = other.parent;
        while (walker != null) {
            if (walker == this || walker.getId().equals(this.id)) {
                return true;
            }
            walker = walker.parent;
        }
        return false;
    }

    /**
     * This category and everything beneath it, at any depth.
     * <p>
     * This is what "filtering by a parent category includes its descendants"
     * needs: selecting Non-fiction must show books filed only under Financial
     * Markets three levels down. The database answers the same question with a
     * recursive query; this is the in-memory equivalent.
     *
     * @return this category followed by all descendants
     */
    public List<Category> getSubtree() {
        List<Category> collected = new ArrayList<>();
        collectSubtree(collected);
        return collected;
    }

    /**
     * Adds this category and its descendants to the given list.
     * <p>
     * Recursive rather than iterative because the shape of the method then
     * matches the shape of the data: handle this node, then ask each child to
     * do the same.
     *
     * @param collected the list being filled
     */
    private void collectSubtree(List<Category> collected) {
        collected.add(this);
        for (int i = 0; i < children.size(); i++) {
            children.get(i).collectSubtree(collected);
        }
    }

    /**
     * How deep this category sits, counting a root as zero.
     *
     * @return the number of categories above this one
     */
    public int getDepth() {
        int depth = 0;
        Category walker = parent;
        while (walker != null) {
            depth++;
            walker = walker.parent;
        }
        return depth;
    }

    /**
     * The full path from the root, for display.
     *
     * @return names joined by " &gt; ", e.g.
     *         "Non-fiction &gt; Finance &gt; Financial Markets"
     */
    public String getPath() {
        // Build from this node upwards, then reverse, since walking down from
        // the root would mean searching for the right branch at every level.
        List<String> names = new ArrayList<>();
        Category walker = this;
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
     * Identity is the id alone, so renaming a category keeps it the same
     * category and it stays findable in a HashMap or HashSet.
     *
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Category)) {
            return false;
        }
        return id.equals(((Category) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return the full path, which is more useful than the bare name */
    @Override
    public String toString() {
        return getPath();
    }
}
