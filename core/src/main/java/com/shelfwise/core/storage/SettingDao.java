package com.shelfwise.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads and writes the {@code app_setting} key/value table: settings that
 * belong to the collection itself and so must travel with the database
 * file. The currency is the one in use. Settings about this install (the
 * data folder, the user, the theme) live in {@code settings.json} instead.
 */
public class SettingDao {

    /** Key under which the collection's currency code is stored. */
    public static final String CURRENCY = "currency";

    /**
     * @param connection an open connection
     * @param key        the setting's name
     * @return its value, or null if it has never been set
     * @throws SQLException if the query fails
     */
    public String get(Connection connection, String key) throws SQLException {
        String sql = "SELECT setting_value FROM app_setting WHERE setting_key = ?";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, key);
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    return rows.getString(1);
                }
                return null;
            }
        }
    }

    /**
     * Sets a value, replacing any earlier one. {@code INSERT OR REPLACE}
     * works because the key is the primary key: a second insert with the
     * same key replaces the row instead of failing.
     *
     * @param connection an open connection
     * @param key        the setting's name
     * @param value      the value; may be null
     * @throws SQLException if the write fails
     */
    public void put(Connection connection, String key, String value) throws SQLException {
        String sql = "INSERT OR REPLACE INTO app_setting (setting_key, setting_value) VALUES (?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, key);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }
}
