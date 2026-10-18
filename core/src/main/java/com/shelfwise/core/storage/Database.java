package com.shelfwise.core.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One SQLite database file: opening connections to it and creating its
 * tables.
 * <p>
 * <strong>JDBC is itself interfaces and runtime polymorphism.</strong>
 * {@link Connection} and {@link Statement} are interfaces. This class never
 * names a SQLite class: {@link DriverManager} reads the {@code jdbc:sqlite:}
 * prefix, finds the driver on the classpath, and returns its own concrete
 * connection object through the {@code Connection} type. Every call made on
 * it is dispatched at runtime to SQLite's implementation, the same pattern
 * as {@code MetadataProvider} in the service layer.
 * <p>
 * <strong>Foreign keys must be switched on for every connection.</strong>
 * SQLite ignores them by default, so without the pragma every
 * {@code ON DELETE RESTRICT} in the schema would silently do nothing. Having
 * one {@link #openConnection()} that every caller goes through means no
 * caller can forget it.
 */
public class Database {

    /** The schema, on the classpath, shared with the RDBMS course project. */
    private static final String SCHEMA_RESOURCE = "/schema.sql";

    /** The database file. Injected, never hardcoded. */
    private final Path file;

    /**
     * @param file where the database file lives or will be created
     * @throws IllegalArgumentException if file is null
     */
    public Database(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("Database file path must not be null.");
        }
        this.file = file;
    }

    /** @return the database file */
    public Path getFile() {
        return file;
    }

    /**
     * Opens a new connection with foreign keys enforced. The caller closes
     * it, normally with try-with-resources.
     *
     * @return an open connection
     * @throws SQLException if the file cannot be opened
     */
    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            // Do not leak an open connection the caller never received.
            connection.close();
            throw e;
        }
        return connection;
    }

    /**
     * Creates every table, index and view, if they do not already exist.
     * Safe to run on every start: the schema uses {@code IF NOT EXISTS}
     * throughout. Creates the folder holding the file if needed.
     *
     * @throws IOException  if the schema cannot be read or the folder made
     * @throws SQLException if a statement fails
     */
    public void initialiseSchema() throws IOException, SQLException {
        Path folder = file.toAbsolutePath().getParent();
        if (folder != null) {
            Files.createDirectories(folder);
        }
        List<String> statements = readSchemaStatements();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            for (int i = 0; i < statements.size(); i++) {
                statement.execute(statements.get(i));
            }
        }
    }

    /**
     * Reads {@code schema.sql} from the classpath and splits it into
     * statements.
     * <p>
     * JDBC runs one statement per call, so the file has to be split on
     * {@code ;}. Comments are removed first, because several comments
     * contain a semicolon in their prose and would otherwise be cut into
     * broken statements. This works because the schema never puts
     * {@code --} or {@code ;} inside a quoted string.
     *
     * @return the statements, in file order, without trailing semicolons
     * @throws IOException if the resource is missing or unreadable
     */
    static List<String> readSchemaStatements() throws IOException {
        StringBuilder sql = new StringBuilder();
        try (InputStream in = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IOException("Schema resource " + SCHEMA_RESOURCE + " is missing.");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    int comment = line.indexOf("--");
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    sql.append(line).append('\n');
                    line = reader.readLine();
                }
            }
        }

        List<String> statements = new ArrayList<>();
        String[] parts = sql.toString().split(";");
        for (int i = 0; i < parts.length; i++) {
            String statement = parts[i].trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return statements;
    }
}
