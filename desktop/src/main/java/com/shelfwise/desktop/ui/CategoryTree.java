package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.service.CollectionManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The category tree as an {@link Organisable} (and so also a
 * {@link TreePicker.Source}): what the picker and the Organise page need to
 * show, create, rename, move and delete categories. {@link LocationTree} is
 * the same for locations; both screens treat the two alike through the
 * interface (runtime polymorphism).
 * <p>
 * Every change goes through the {@code CollectionManager}, never straight
 * to the model, so the manager's rules apply (ADR 0006). The manager is
 * asked for afresh on every call, because "Keep theirs" replaces it
 * (see {@link Session}).
 */
final class CategoryTree implements Organisable<Category> {

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

    @Override
    public String id(Category node) {
        return node.getId();
    }

    @Override
    public Category parent(Category node) {
        return node.getParent();
    }

    @Override
    public List<Category> subtree(Category node) {
        return node.getSubtree();
    }

    @Override
    public List<String> idsOf(Book book) {
        return book.getCategoryIds();
    }

    @Override
    public void rename(Category node, String newName) {
        session.getManager().renameCategory(node, newName.trim());
    }

    @Override
    public void move(Category node, Category newParent) {
        session.getManager().moveCategory(node, newParent);
    }

    @Override
    public void delete(Category node) throws ShelfWiseException {
        session.getManager().deleteCategory(node);
    }

    @Override
    public void restore(Category node, Category parent) {
        // A deleted category is no longer indexed, so it can be added again
        // as it was: same object, same id, sub-categories still attached.
        if (parent == null) {
            session.getManager().addCategory(node);
        } else {
            session.getManager().addCategory(node, parent);
        }
    }

    /**
     * E101 for categories, done by {@link CollectionManager#unfile}. The
     * manager does not say what it changed, so each item's filings inside
     * the subtree are written down first: that record is what Undo puts back.
     */
    @Override
    public List<Runnable> release(Category node, final Category moveTo) {
        final CollectionManager manager = session.getManager();
        Set<String> ids = new HashSet<>();
        List<Category> nodes = node.getSubtree();
        for (int i = 0; i < nodes.size(); i++) {
            ids.add(nodes.get(i).getId());
        }

        // Live and trashed items alike: unfile changes both.
        List<CollectionItem> items = new ArrayList<>(manager.getItems());
        items.addAll(manager.getTrash());
        List<Runnable> undo = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            final CollectionItem item = items.get(i);
            final List<String> released = new ArrayList<>();
            List<String> own = item.getCategoryIds();
            for (int j = 0; j < own.size(); j++) {
                if (ids.contains(own.get(j))) {
                    released.add(own.get(j));
                }
            }
            if (released.isEmpty()) {
                continue;
            }
            // If the item was already under moveTo, unfile adds nothing, so
            // Undo must not take that filing away.
            final boolean addsMoveTo = moveTo != null && !own.contains(moveTo.getId());
            undo.add(new Runnable() {
                @Override
                public void run() {
                    if (addsMoveTo) {
                        // The manager has no "unfile one item" method; this
                        // only reverses the filing unfile itself added.
                        item.removeCategoryId(moveTo.getId());
                    }
                    for (int k = 0; k < released.size(); k++) {
                        manager.fileUnder(item, manager.findCategoryById(released.get(k)));
                    }
                }
            });
        }
        manager.unfile(node, moveTo);
        return undo;
    }
}
