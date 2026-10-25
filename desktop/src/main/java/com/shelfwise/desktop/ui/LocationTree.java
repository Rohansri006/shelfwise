package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Location;

import java.util.List;

/**
 * The location tree as a {@link TreePicker.Source}; the twin of
 * {@link CategoryTree}. The two are separate classes rather than one shared
 * base for the same reason {@code Category} and {@code Location} are: the
 * duplication is a few lines, and each stays simple to read.
 */
final class LocationTree implements TreePicker.Source<Location> {

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
}
