package com.shelfwise.core.storage;

import com.shelfwise.core.model.Category;

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
 * Reads and writes the {@code category} table.
 * <p>
 * The tree is stored the relational way: each row holds only its
 * {@code parent_id}, a foreign key into the same table. In memory each
 * {@link Category} also holds object links to its parent and children, which
 * are rebuilt here after loading.
 * <p>
 * Every method takes the {@link Connection} from its caller instead of
 * opening one, so a whole save can run inside a single transaction that
 * {@code SqliteStorage} commits or rolls back.
 */
public class CategoryDao {

    /**
     * Writes every category. Parents are inserted before their children,
     * because a child row's {@code parent_id} must already exist when it is
     * inserted.
     *
     * @param connection an open connection
     * @param categories every category node, in any order
     * @throws SQLException if an insert fails
     */
    public void insertAll(Connection connection, List<Category> categories) throws SQLException {
        List<Category> ordered = new ArrayList<>(categories);
        // Sort shallowest first: roots (depth 0), then their children, and
        // so on. Comparator is an interface; this anonymous class overrides
        // its one method.
        Collections.sort(ordered, new Comparator<Category>() {
            @Override
            public int compare(Category a, Category b) {
                return Integer.compare(a.getDepth(), b.getDepth());
            }
        });

        String sql = "INSERT INTO category (id, name, parent_id) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int i = 0; i < ordered.size(); i++) {
                Category category = ordered.get(i);
                insert.setString(1, category.getId());
                insert.setString(2, category.getName());
                insert.setString(3, category.getParentId());
                insert.executeUpdate();
            }
        }
    }

    /**
     * Deletes every category, leaves first (see {@link TreeTables}).
     * Rows in {@code item_category} must already be gone.
     *
     * @param connection an open connection
     * @throws SQLException if a delete fails
     */
    public void deleteAll(Connection connection) throws SQLException {
        TreeTables.deleteAllLeavesFirst(connection, "category");
    }

    /**
     * Reads every category and links each one to its parent.
     * <p>
     * Two passes. The first creates every object and remembers each one's
     * parent id; the second calls {@link Category#addChild}, which sets both
     * directions of the link. Linking cannot happen in the first pass,
     * because a child's row may come back before its parent's.
     *
     * @param connection an open connection
     * @return every category node, roots and descendants alike
     * @throws SQLException if the query fails or a parent id points nowhere
     */
    public List<Category> loadAll(Connection connection) throws SQLException {
        List<Category> all = new ArrayList<>();
        Map<String, Category> byId = new HashMap<>();
        Map<String, String> parentIdOf = new HashMap<>();

        String sql = "SELECT id, name, parent_id FROM category";
        try (PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Category category = new Category(rows.getString("id"), rows.getString("name"));
                all.add(category);
                byId.put(category.getId(), category);
                String parentId = rows.getString("parent_id");
                if (parentId != null) {
                    parentIdOf.put(category.getId(), parentId);
                }
            }
        }

        for (int i = 0; i < all.size(); i++) {
            Category child = all.get(i);
            String parentId = parentIdOf.get(child.getId());
            if (parentId != null) {
                Category parent = byId.get(parentId);
                if (parent == null) {
                    throw new SQLException("Category \"" + child.getName()
                            + "\" names a parent " + parentId + " that does not exist.");
                }
                parent.addChild(child);
            }
        }
        return all;
    }
}
