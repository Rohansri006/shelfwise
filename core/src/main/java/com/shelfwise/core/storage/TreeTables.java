package com.shelfwise.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQL shared by the two self-referencing tables, {@code category} and
 * {@code location}.
 * <p>
 * Emptying such a table is not a single {@code DELETE}. Each row's
 * {@code parent_id} is {@code ON DELETE RESTRICT}, and SQLite applies
 * {@code RESTRICT} the moment a row is deleted, not at the end of the
 * statement. A plain {@code DELETE FROM category} therefore fails as soon as
 * it reaches a parent before its children (verified against a live
 * database). Deleting only the current leaves, and repeating until nothing
 * is left, never deletes a parent while a child still points at it.
 */
final class TreeTables {

    /** Not instantiable: a holder for one static helper. */
    private TreeTables() {
    }

    /**
     * Deletes every row of a tree table, leaves first.
     * <p>
     * Each pass removes the rows no other row names as its parent. A tree
     * of depth <em>d</em> empties in <em>d</em> + 1 passes.
     *
     * @param connection an open connection, usually inside a transaction
     * @param table      "category" or "location"; a fixed name from this
     *                   package, never user input, so it is safe to put
     *                   into the SQL text
     * @throws SQLException if a delete fails
     */
    static void deleteAllLeavesFirst(Connection connection, String table) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE id NOT IN "
                + "(SELECT parent_id FROM " + table + " WHERE parent_id IS NOT NULL)";
        try (Statement statement = connection.createStatement()) {
            int deleted = statement.executeUpdate(sql);
            while (deleted > 0) {
                deleted = statement.executeUpdate(sql);
            }
        }
    }
}
