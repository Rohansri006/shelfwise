package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.service.CollectionManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The location tree as an {@link Organisable}; the twin of
 * {@link CategoryTree}. The two are separate classes rather than one shared
 * base for the same reason {@code Category} and {@code Location} are: the
 * duplication is a few lines, and each stays simple to read.
 */
final class LocationTree implements Organisable<Location> {

    private final Session session;
    private final Shell shell;

    /**
     * @param session the open collection
     * @param shell   the window, to save after a location is created
     */
    LocationTree(Session session, Shell shell) {
        this.session = session;
        this.shell = shell;
    }

    @Override
    public List<Location> roots() {
        return session.getManager().getRootLocations();
    }

    @Override
    public List<Location> children(Location node) {
        return node.getChildren();
    }

    @Override
    public String name(Location node) {
        return node.getName();
    }

    @Override
    public Location create(String name, Location parent) {
        // Blank or sibling-clashing names throw IllegalArgumentException,
        // which the picker shows in the popup.
        Location location = new Location(name.trim());
        if (parent == null) {
            session.getManager().addLocation(location);
        } else {
            session.getManager().addLocation(location, parent);
        }
        shell.saveChanges();
        return location;
    }

    @Override
    public String id(Location node) {
        return node.getId();
    }

    @Override
    public Location parent(Location node) {
        return node.getParent();
    }

    @Override
    public List<Location> subtree(Location node) {
        return node.getSubtree();
    }

    /**
     * A book is "in" a location when any of its copies is: copies hold the
     * location id, books do not (one-directional, §10).
     */
    @Override
    public List<String> idsOf(Book book) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < book.getCopies().size(); i++) {
            String id = book.getCopies().get(i).getLocationId();
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    @Override
    public void rename(Location node, String newName) {
        session.getManager().renameLocation(node, newName.trim());
    }

    @Override
    public void move(Location node, Location newParent) {
        session.getManager().moveLocation(node, newParent);
    }

    @Override
    public void delete(Location node) throws ShelfWiseException {
        session.getManager().deleteLocation(node);
    }

    @Override
    public void restore(Location node, Location parent) {
        if (parent == null) {
            session.getManager().addLocation(node);
        } else {
            session.getManager().addLocation(node, parent);
        }
    }

    /**
     * E101 for locations, done by {@link CollectionManager#unshelve}: every
     * copy inside goes to {@code moveTo}, or off the shelves. Each copy's old
     * location is written down first, for Undo.
     */
    @Override
    public List<Runnable> release(Location node, Location moveTo) {
        final CollectionManager manager = session.getManager();
        Set<String> ids = new HashSet<>();
        List<Location> nodes = node.getSubtree();
        for (int i = 0; i < nodes.size(); i++) {
            ids.add(nodes.get(i).getId());
        }

        // Live and trashed books alike: unshelve moves both.
        List<CollectionItem> items = new ArrayList<>(manager.getItems());
        items.addAll(manager.getTrash());
        List<Runnable> undo = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<Copy> copies = ((Book) items.get(i)).getCopies();
            for (int j = 0; j < copies.size(); j++) {
                final Copy copy = copies.get(j);
                final String oldId = copy.getLocationId();
                if (oldId != null && ids.contains(oldId)) {
                    undo.add(new Runnable() {
                        @Override
                        public void run() {
                            manager.setLocation(copy, manager.findLocationById(oldId));
                        }
                    });
                }
            }
        }
        manager.unshelve(node, moveTo);
        return undo;
    }
}
