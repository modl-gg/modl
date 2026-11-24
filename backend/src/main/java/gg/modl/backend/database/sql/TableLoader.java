package gg.modl.backend.database.sql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

final class TableLoader {
    private static final int BATCH_ROWS = 500;
    private static final long BATCH_CHARACTERS = 8L * 1024 * 1024;

    private final Connection connection;
    private final DSLContext dsl;
    private final InstanceFence fence;

    TableLoader(Connection connection, DSLContext dsl, InstanceFence fence) {
        this.connection = connection;
        this.dsl = dsl;
        this.fence = fence;
    }

    void load(List<SchemaTable> parentsFirst, RowFeed feed) throws IOException, SQLException {
        connection.setAutoCommit(false);
        for (SchemaTable table : parentsFirst.reversed()) {
            fence.requireServing();
            dsl.deleteFrom(table.table()).execute();
        }
        commit();
        for (SchemaTable table : parentsFirst) {
            PendingRows pending = new PendingRows(table);
            feed.feed(table, pending::add);
            pending.flush();
        }
    }

    private void commit() {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw DatabaseToolException.failedTo("commit the copied rows", e);
        }
    }

    private final class PendingRows {
        private final SchemaTable table;
        private final List<Object[]> rows = new ArrayList<>(BATCH_ROWS);
        private long characters;

        private PendingRows(SchemaTable table) {
            this.table = table;
        }

        private void add(List<Object> values) {
            rows.add(values.toArray());
            for (Object value : values) {
                if (value instanceof String text) {
                    characters += text.length();
                }
            }
            if (rows.size() == BATCH_ROWS || characters >= BATCH_CHARACTERS) {
                flush();
            }
        }

        private void flush() {
            if (rows.isEmpty()) {
                return;
            }
            fence.requireServing();
            BatchBindStep batch = dsl.batch(dsl.insertInto(table.table(), table.fields()).values(new Object[table.columns().size()]));
            for (Object[] row : rows) {
                batch = batch.bind(row);
            }
            try {
                batch.execute();
            } catch (DataAccessException e) {
                throw rejected(e);
            }
            commit();
            rows.clear();
            characters = 0;
        }

        private DatabaseToolException rejected(DataAccessException failure) {
            SQLException cause = failure.getCause(SQLException.class);
            String code = cause == null ? "unknown" : cause.getSQLState() + "/" + cause.getErrorCode();
            return new DatabaseToolException("The database rejected the rows of " + table.name() + " (error " + code + ")", failure);
        }
    }
}
