package com.shelfwise.core.storage;

import com.shelfwise.core.model.Series;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the {@code series} table.
 * <p>
 * Not one of the four DAOs SPEC §9 lists. Series has its own table and
 * belongs to no other DAO's subject, so it gets its own small class rather
 * than being tucked inside the book DAO.
 */
public class SeriesDao {

    /**
     * @param connection an open connection
     * @param series     every series
     * @throws SQLException if an insert fails
     */
    public void insertAll(Connection connection, List<Series> series) throws SQLException {
        String sql = "INSERT INTO series (id, name, total_books) VALUES (?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            for (int i = 0; i < series.size(); i++) {
                Series s = series.get(i);
                insert.setString(1, s.getId());
                insert.setString(2, s.getName());
                insert.setInt(3, s.getTotalBooks());
                insert.executeUpdate();
            }
        }
    }

    /**
     * Deletes every series. Any book still pointing at one has its
     * {@code series_id} set to null by the schema's {@code ON DELETE SET NULL}.
     *
     * @param connection an open connection
     * @throws SQLException if the delete fails
     */
    public void deleteAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM series");
        }
    }

    /**
     * @param connection an open connection
     * @return every series
     * @throws SQLException if the query fails
     */
    public List<Series> loadAll(Connection connection) throws SQLException {
        List<Series> all = new ArrayList<>();
        String sql = "SELECT id, name, total_books FROM series";
        try (PreparedStatement query = connection.prepareStatement(sql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                all.add(new Series(rows.getString("id"), rows.getString("name"),
                        rows.getInt("total_books")));
            }
        }
        return all;
    }
}
