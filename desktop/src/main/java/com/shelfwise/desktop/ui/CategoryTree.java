package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Category;

import java.util.List;

/**
 * The category tree as a {@link TreePicker.Source}: what the picker needs to
 * show categories and create new ones. {@link LocationTree} is the same for
 * locations; the picker treats both alike through the interface (runtime
 * polymorphism).
 * <p>
 * Every change goes through the {@code CollectionManager}, never straight
 * to the model, so the manager's rules apply (ADR 0006). The manager is
 * asked for afresh on every call, because "Keep theirs" replaces it
 * (see {@link Session}).
 */
final class CategoryTree implements TreePicker.Source<Category> {

    private final Session session;
    private final Shell shell;

    /**
     * @param session the open collection
     * @param shell   the window, to save after a category is created
     */
    CategoryTree(Session session, Shell shell) {
        this.session = session;
        this.shell = shell;
    }

    @Override
    public List<Category> roots() {
        return session.getManager().getRootCategories();
    }

    @Override
    public List<Category> children(Category node) {
        return node.getChildren();
    }

    @Override
    public String name(Category node) {
        return node.getName();
    }

    @Override
    public Category create(String name, Category parent) {
        // The constructor refuses a blank name; the manager refuses a name a
        // sibling already has. Both throw IllegalArgumentException, which the
        // picker shows in the popup.
        Category category = new Category(name.trim());
        if (parent == null) {
            session.getManager().addCategory(category);
        } else {
            session.getManager().addCategory(category, parent);
        }
        // A new category is a logical operation of its own (ADR 0001).
        shell.saveChanges();
        return category;
    }
}
