package com.shelfwise.core.storage;

import com.shelfwise.core.model.Location;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the {@code location} table.
 * <p>
 * Works exactly like {@link CategoryDao}: parents inserted before children,
 * leaves deleted first, links rebuilt in a second pass after loading. The
 * two are separate classes because {@link Location} and
 * {@link com.shelfwise.core.model.Category} deliberately share no base class.
 */
public class LocationDao {

    /**
     * Writes every location, parents before children.
     *
     * @param connection an open connection
     * @param locations  every location node, in any order
     * @throws SQLException if an insert fails
     */
    public void insertAll(Connection connection, List<Location> locations) throws SQLException {
        List<Location> ordered = new ArrayList<>(locations);
        Collections.sort(ordered, new Comparator<Location>() {
            @Override
            public int compare(Location a, Location b) {
                return Integer.compare(a.getDepth(), b.getDepth());
            }
        });

        String sql = "INSERT INTO location (id, name, parent_id) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int i = 0; i < ordered.size(); i++) {
                Location location = ordered.get(i);
                insert.setString(1, location.getId());
                insert.setString(2, location.getName());
                insert.setString(3, location.getParentId());
                insert.executeUpdate();
            }
        }
    }

    /**
     * Deletes every location, leaves first. Copies must already be gone,
     * since {@code copy.location_id} is {@code RESTRICT} too.
     *
     * @param connection an open connection
     * @throws SQLException if a delete fails
     */
    public void deleteAll(Connection connection) throws SQLException {
        TreeTables.deleteAllLeavesFirst(connection, "location");
    }

    /**
     * Reads every location and links each one to its parent, in two passes.
     *
     * @param connection an open connection
     * @return every location node
     * @throws SQLException if the query fails or a parent id points nowhere
     */
    public List<Location> loadAll(Connection connection) throws SQLException {
        List<Location> all = new ArrayList<>();
        Map<String, Location> byId = new HashMap<>();
        Map<String, String> parentIdOf = new HashMap<>();

        String sql = "SELECT id, name, parent_id FROM location";
        try (PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Location location = new Location(rows.getString("id"), rows.getString("name"));
                all.add(location);
                byId.put(location.getId(), location);
                String parentId = rows.getString("parent_id");
                if (parentId != null) {
                    parentIdOf.put(location.getId(), parentId);
                }
            }
        }

        for (int i = 0; i < all.size(); i++) {
            Location child = all.get(i);
            String parentId = parentIdOf.get(child.getId());
            if (parentId != null) {
                Location parent = byId.get(parentId);
                if (parent == null) {
                    throw new SQLException("Location \"" + child.getName()
                            + "\" names a parent " + parentId + " that does not exist.");
                }
                parent.addChild(child);
            }
        }
        return all;
    }
}
