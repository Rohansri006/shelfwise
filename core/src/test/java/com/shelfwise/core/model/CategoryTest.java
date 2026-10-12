package com.shelfwise.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the {@link Category} tree. */
class CategoryTest {

    /** Builds Non-fiction &gt; Finance &gt; Financial Markets, the worked
     *  example from the specification. */
    private Category buildTree() {
        Category nonFiction = new Category("Non-fiction");
        Category finance = new Category("Finance");
        Category markets = new Category("Financial Markets");
        nonFiction.addChild(finance);
        finance.addChild(markets);
        return nonFiction;
    }

    @Test
    @DisplayName("a new category is a root with no children")
    void newCategoryIsRoot() {
        Category c = new Category("Fiction");
        assertTrue(c.isRoot());
        assertTrue(c.isLeaf());
        assertNull(c.getParentId());
        assertNull(c.getParent());
        assertEquals(0, c.getDepth());
    }

    @Test
    @DisplayName("blank names are rejected")
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Category("   ");
            }
        });
    }

    @Test
    @DisplayName("addChild sets both directions of the link")
    void addChildSetsBothDirections() {
        Category parent = new Category("Non-fiction");
        Category child = new Category("Finance");
        parent.addChild(child);

        // Both halves matter: parentId is what gets saved, while the object
        // references are what the UI navigates. If they disagree, the bug
        // only appears after a save and reload.
        assertSame(parent, child.getParent());
        assertEquals(parent.getId(), child.getParentId());
        assertEquals(1, parent.getChildren().size());
        assertFalse(child.isRoot());
    }

    @Test
    @DisplayName("depth and path reflect position in the tree")
    void depthAndPath() {
        Category root = buildTree();
        Category finance = root.getChildren().get(0);
        Category markets = finance.getChildren().get(0);

        assertEquals(0, root.getDepth());
        assertEquals(1, finance.getDepth());
        assertEquals(2, markets.getDepth());

        assertEquals("Non-fiction > Finance > Financial Markets", markets.getPath());
        assertEquals("Non-fiction", root.getPath());
    }

    @Test
    @DisplayName("getSubtree returns the category and every descendant")
    void subtreeIncludesDescendants() {
        // This is what makes "filter by a parent includes everything beneath
        // it" work - selecting Non-fiction must reach three levels down.
        Category root = buildTree();
        List<Category> subtree = root.getSubtree();

        assertEquals(3, subtree.size());
        assertEquals("Non-fiction", subtree.get(0).getName());
        assertEquals("Finance", subtree.get(1).getName());
        assertEquals("Financial Markets", subtree.get(2).getName());

        // A leaf's subtree is just itself.
        assertEquals(1, subtree.get(2).getSubtree().size());
    }

    @Test
    @DisplayName("sibling names must be unique, case-insensitively")
    void siblingNamesUnique() {
        final Category parent = new Category("Non-fiction");
        parent.addChild(new Category("Finance"));

        // Same name, different capitalisation: the user means the same thing,
        // so silently creating a second one would be worse than refusing.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        parent.addChild(new Category("finance"));
                    }
                });
        assertTrue(e.getMessage().contains("already has a sub-category"), e.getMessage());
    }

    @Test
    @DisplayName("the same name is allowed under different parents")
    void sameNameUnderDifferentParents() {
        // Deliberately permitted: uniqueness is per-parent, not global. A
        // global rule gets frustrating fast in a deep tree. This mirrors the
        // UNIQUE (parent_id, name) constraint in schema.sql.
        Category nonFiction = new Category("Non-fiction");
        Category reference = new Category("Reference");
        nonFiction.addChild(new Category("Finance"));
        reference.addChild(new Category("Finance"));

        assertEquals("Non-fiction > Finance",
                nonFiction.getChildren().get(0).getPath());
        assertEquals("Reference > Finance",
                reference.getChildren().get(0).getPath());
    }

    @Test
    @DisplayName("a category cannot be its own parent")
    void cannotParentItself() {
        final Category c = new Category("Finance");
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                c.addChild(c);
            }
        });
    }

    @Test
    @DisplayName("a category cannot be moved beneath its own descendant")
    void cannotCreateCycle() {
        // The dangerous case: dragging Non-fiction into Financial Markets.
        // Unchecked, that whole branch would detach from the tree and be lost.
        final Category root = buildTree();
        final Category markets = root.getChildren().get(0).getChildren().get(0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        markets.addChild(root);
                    }
                });
        assertTrue(e.getMessage().contains("already above it"), e.getMessage());
    }

    @Test
    @DisplayName("isAncestorOf walks the whole way up")
    void isAncestorOf() {
        Category root = buildTree();
        Category finance = root.getChildren().get(0);
        Category markets = finance.getChildren().get(0);

        assertTrue(root.isAncestorOf(markets));
        assertTrue(root.isAncestorOf(finance));
        assertTrue(finance.isAncestorOf(markets));
        assertFalse(markets.isAncestorOf(root));
        // Not an ancestor of itself.
        assertFalse(root.isAncestorOf(root));
        assertFalse(root.isAncestorOf(null));
    }

    @Test
    @DisplayName("moving a category detaches it from its previous parent")
    void moveDetachesFromOldParent() {
        Category nonFiction = new Category("Non-fiction");
        Category reference = new Category("Reference");
        Category finance = new Category("Finance");

        nonFiction.addChild(finance);
        reference.addChild(finance);

        // Without the detach step it would appear under both, and a save
        // would then write a parent that contradicts the tree.
        assertEquals(0, nonFiction.getChildren().size());
        assertEquals(1, reference.getChildren().size());
        assertEquals(reference.getId(), finance.getParentId());
    }

    @Test
    @DisplayName("removeChild promotes the child to a root")
    void removeChildPromotesToRoot() {
        Category root = buildTree();
        Category finance = root.getChildren().get(0);

        assertTrue(root.removeChild(finance));
        assertTrue(finance.isRoot());
        assertNull(finance.getParentId());
        // Its own subtree is untouched: Financial Markets moves with it.
        assertEquals(2, finance.getSubtree().size());

        assertFalse(root.removeChild(finance));
    }

    @Test
    @DisplayName("children getter is unmodifiable, so the tree cannot be bypassed")
    void childrenUnmodifiable() {
        final Category root = new Category("Non-fiction");
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                root.getChildren().add(new Category("Sneaky"));
            }
        });
    }

    @Test
    @DisplayName("identity survives renaming, so HashSet membership holds")
    void identitySurvivesRename() {
        // The marking scheme wants HashSet use; a HashSet is only correct if
        // equals and hashCode stay stable, which is why both use the id.
        Category finance = new Category("Finance");
        Set<Category> set = new HashSet<>();
        set.add(finance);

        finance.setName("Personal Finance");
        assertTrue(set.contains(finance));
        assertEquals(1, set.size());

        // A different category with the same name is still a different one.
        assertFalse(set.contains(new Category("Personal Finance")));
    }
}
