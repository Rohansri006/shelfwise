package com.shelfwise.core.storage;

import com.shelfwise.core.model.Priority;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the {@code wishlist} and {@code wishlist_entry} tables.
 * <p>
 * One DAO for both tables because an entry only exists inside its list:
 * composition. The database says the same thing with
 * {@code ON DELETE CASCADE}, so deleting the lists deletes their entries.
 * <p>
 * <strong>Order.</strong> Neither table has a position column. Rows are
 * read back {@code ORDER BY rowid}, SQLite's hidden insertion counter.
 * Because every save rewrites the tables in list order, that is the order
 * the user had. A port to another database would need an explicit position
 * column instead, since {@code rowid} is SQLite-specific.
 */
public class WishlistDao {

    /**
     * Writes every wishlist, then every entry of each.
     *
     * @param connection an open connection
     * @param wishlists  every wishlist
     * @throws SQLException if an insert fails
     */
    public void insertAll(Connection connection, List<Wishlist> wishlists) throws SQLException {
        String listSql = "INSERT INTO wishlist (id, name) VALUES (?, ?)";
        String entrySql = "INSERT INTO wishlist_entry (id, wishlist_id, title, isbn, author, "
                + "priority, expected_price, store, rare) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insertList = connection.prepareStatement(listSql);
             PreparedStatement insertEntry = connection.prepareStatement(entrySql)) {
            for (int i = 0; i < wishlists.size(); i++) {
                Wishlist wishlist = wishlists.get(i);
                insertList.setString(1, wishlist.getId());
                insertList.setString(2, wishlist.getName());
                insertList.executeUpdate();

                List<WishlistEntry> entries = wishlist.getEntries();
                for (int j = 0; j < entries.size(); j++) {
                    WishlistEntry entry = entries.get(j);
                    insertEntry.setString(1, entry.getId());
                    insertEntry.setString(2, wishlist.getId());
                    insertEntry.setString(3, entry.getTitle());
                    insertEntry.setString(4, entry.getIsbn());
                    insertEntry.setString(5, entry.getAuthor());
                    // Enums are stored by name(), not by label: names never
                    // change with wording, and the schema's CHECK lists them.
                    insertEntry.setString(6, entry.getPriority().name());
                    insertEntry.setDouble(7, entry.getExpectedPrice());
                    insertEntry.setString(8, entry.getStore());
                    insertEntry.setInt(9, entry.isRare() ? 1 : 0);
                    insertEntry.executeUpdate();
                }
            }
        }
    }

    /**
     * Deletes every wishlist; their entries go with them by cascade.
     *
     * @param connection an open connection
     * @throws SQLException if the delete fails
     */
    public void deleteAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM wishlist");
        }
    }

    /**
     * Reads every wishlist with its entries, in the order they were saved.
     * <p>
     * Two queries, not one per list: the lists first, then all entries,
     * each handed to its list through a {@code HashMap} keyed by list id.
     *
     * @param connection an open connection
     * @return every wishlist, each holding its entries
     * @throws SQLException if a query fails
     */
    public List<Wishlist> loadAll(Connection connection) throws SQLException {
        List<Wishlist> all = new ArrayList<>();
        Map<String, Wishlist> byId = new HashMap<>();

        String listSql = "SELECT id, name FROM wishlist ORDER BY rowid";
        try (PreparedStatement query = connection.prepareStatement(listSql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Wishlist wishlist = new Wishlist(rows.getString("id"), rows.getString("name"));
                all.add(wishlist);
                byId.put(wishlist.getId(), wishlist);
            }
        }

        String entrySql = "SELECT id, wishlist_id, title, isbn, author, priority, "
                + "expected_price, store, rare FROM wishlist_entry ORDER BY rowid";
        try (PreparedStatement query = connection.prepareStatement(entrySql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                WishlistEntry entry = new WishlistEntry(rows.getString("id"), rows.getString("title"));
                entry.setIsbn(rows.getString("isbn"));
                entry.setAuthor(rows.getString("author"));
                entry.setPriority(Priority.valueOf(rows.getString("priority")));
                entry.setExpectedPrice(rows.getDouble("expected_price"));
                entry.setStore(rows.getString("store"));
                entry.setRare(rows.getInt("rare") == 1);
                // The foreign key guarantees the list exists.
                byId.get(rows.getString("wishlist_id")).addEntry(entry);
            }
        }
        return all;
    }
}
