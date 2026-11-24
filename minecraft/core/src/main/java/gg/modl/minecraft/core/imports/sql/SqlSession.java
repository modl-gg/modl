package gg.modl.minecraft.core.imports.sql;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SqlSession implements AutoCloseable {
    private static final int FETCH_SIZE = 500;

    private final Connection connection;
    private final SqlDialect dialect;
    private final Release release;
    private final Map<String, List<String>> columnsByTable = new HashMap<>();
    private volatile @Nullable PreparedStatement running;
    private @Nullable List<String> tables;

    private SqlSession(Connection connection, SqlDialect dialect, Release release) {
        this.connection = connection;
        this.dialect = dialect;
        this.release = release;
    }

    public static SqlSession owning(Connection connection, Runnable afterClose) throws SQLException {
        return of(connection, () -> {
            try {
                if (!connection.getAutoCommit()) connection.rollback();
            } finally {
                try {
                    connection.close();
                } finally {
                    afterClose.run();
                }
            }
        });
    }

    public static SqlSession borrowing(Connection connection, Release release) throws SQLException {
        return of(connection, release);
    }

    private static SqlSession of(Connection connection, Release release) throws SQLException {
        try {
            return new SqlSession(connection, SqlDialect.of(connection), release);
        } catch (SQLException | RuntimeException unsupported) {
            try {
                release.release();
            } catch (SQLException | RuntimeException releaseFailed) {
                unsupported.addSuppressed(releaseFailed);
            }
            throw unsupported;
        }
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public String quote(String identifier) {
        return dialect.quote(identifier);
    }

    public void query(String sql, Binder binder, RowReader reader) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(FETCH_SIZE);
            binder.bind(statement);
            running = statement;
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) reader.read(row);
            } finally {
                running = null;
            }
        }
    }

    public void query(String sql, RowReader reader) throws SQLException {
        query(sql, statement -> { }, reader);
    }

    public void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public long count(String sql, Binder binder) throws SQLException {
        long[] counted = {0};
        query(sql, binder, row -> counted[0] = row.getLong(1));
        return counted[0];
    }

    public long count(String sql) throws SQLException {
        return count(sql, statement -> { });
    }

    public boolean hasTable(String table) throws SQLException {
        return storedName(tables(), table) != null;
    }

    public boolean hasColumn(String table, String column) throws SQLException {
        String stored = storedName(tables(), table);
        return stored != null && storedName(columnsOf(stored), column) != null;
    }

    public Cursor cursor(String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            statement.setFetchSize(FETCH_SIZE);
            running = statement;
            return new Cursor(statement, statement.executeQuery());
        } catch (SQLException | RuntimeException failure) {
            running = null;
            statement.close();
            throw failure;
        }
    }

    private List<String> tables() throws SQLException {
        List<String> known = tables;
        if (known != null) return known;
        List<String> names = new ArrayList<>();
        try (ResultSet row = connection.getMetaData().getTables(connection.getCatalog(), schema(), null, null)) {
            while (row.next()) names.add(row.getString("TABLE_NAME"));
        }
        tables = names;
        return names;
    }

    private List<String> columnsOf(String storedTable) throws SQLException {
        List<String> known = columnsByTable.get(storedTable);
        if (known != null) return known;
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet row = metadata.getColumns(connection.getCatalog(), schema(),
                escaped(storedTable, metadata.getSearchStringEscape()), null)) {
            while (row.next()) {
                if (storedTable.equals(row.getString("TABLE_NAME"))) names.add(row.getString("COLUMN_NAME"));
            }
        }
        columnsByTable.put(storedTable, names);
        return names;
    }

    private @Nullable String schema() {
        try {
            return connection.getSchema();
        } catch (SQLException | RuntimeException | AbstractMethodError unsupported) {
            return null;
        }
    }

    private static @Nullable String storedName(List<String> stored, String wanted) {
        String folded = null;
        for (String name : stored) {
            if (name.equals(wanted)) return name;
            if (name.equalsIgnoreCase(wanted)) folded = name;
        }
        return folded;
    }

    private static String escaped(String pattern, @Nullable String escape) {
        if (escape == null || escape.isEmpty()) return pattern;
        return pattern.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    public void cancelRunning() {
        PreparedStatement statement = running;
        if (statement == null) return;
        try {
            statement.cancel();
        } catch (SQLException alreadyDone) {
            running = null;
        }
    }

    @Override
    public void close() {
        try {
            release.release();
        } catch (SQLException | RuntimeException ignored) {
            running = null;
        }
    }

    public static String placeholders(int count) {
        StringBuilder marks = new StringBuilder();
        for (int index = 0; index < count; index++) marks.append(index == 0 ? "?" : ", ?");
        return marks.toString();
    }

    public static Binder bindAll(List<?> values) {
        return statement -> {
            for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
        };
    }

    public final class Cursor implements AutoCloseable {
        private final PreparedStatement statement;
        private final ResultSet row;

        private Cursor(PreparedStatement statement, ResultSet row) {
            this.statement = statement;
            this.row = row;
        }

        public boolean next() throws SQLException {
            return row.next();
        }

        public ResultSet row() {
            return row;
        }

        @Override
        public void close() throws SQLException {
            running = null;
            try {
                row.close();
            } finally {
                statement.close();
            }
        }
    }

    @FunctionalInterface
    public interface Release {
        void release() throws SQLException;
    }

    @FunctionalInterface
    public interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    public interface RowReader {
        void read(ResultSet row) throws SQLException;
    }
}
