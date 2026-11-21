package io.github.rkaushikdev.nozzlewright.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Reading what a database actually holds, out of its information schema. */
final class Catalog {

    private Catalog() {}

    static Set<String> tables(Database database) {
        return strings(
                database,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_type = 'BASE TABLE'");
    }

    static Set<String> indexes(Database database, String table) {
        return strings(
                database,
                "SELECT index_name FROM information_schema.indexes WHERE table_schema = 'public' AND table_name = ?",
                table);
    }

    static Set<String> uniqueConstraints(Database database, String table) {
        return strings(
                database,
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = 'public'"
                        + " AND table_name = ? AND constraint_type = 'UNIQUE'",
                table);
    }

    static List<String> primaryKey(Database database, String table) {
        List<String> columns = new ArrayList<>();
        try (Connection connection = database.dataSource().getConnection();
                ResultSet keys = connection.getMetaData().getPrimaryKeys(null, "public", table)) {
            while (keys.next()) {
                columns.add(keys.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        return columns;
    }

    static long count(Database database, String sql) {
        try (Connection connection = database.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    static void execute(Database database, String sql) {
        try (Connection connection = database.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Set<String> strings(Database database, String sql, String... parameters) {
        Set<String> found = new TreeSet<>();
        try (Connection connection = database.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        return found;
    }
}
