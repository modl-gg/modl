package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;

import gg.modl.backend.database.DatabaseDialect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.jooq.Cursor;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class DatabaseView {
    private static final int FETCH_SIZE = 500;

    private final DSLContext dsl;
    private final SchemaCatalog catalog;
    private final String schemaVersion;
    private final InstanceFence fence;

    private DatabaseView(DSLContext dsl, SchemaCatalog catalog, String schemaVersion, InstanceFence fence) {
        this.dsl = dsl;
        this.catalog = catalog;
        this.schemaVersion = schemaVersion;
        this.fence = fence;
    }

    static DatabaseView of(DatabaseAccess access, Connection connection, DatabaseDialect dialect, DataSource connections, DSLContext dsl)
        throws SQLException {
        SchemaState state = SchemaState.read(access, connection, dialect, connections);
        if (state.version().isEmpty()) {
            throw state.unfinished() ? unfinishedRestore() : notModlDatabase();
        }
        String schemaVersion = state.version().get();
        Set<String> modlTables = modlTables(access.schemas(), state, dialect, schemaVersion);
        List<String> missing = modlTables.stream().filter(table -> !state.tables().contains(table)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new DatabaseToolException("The database is missing tables of modl's schema version " + schemaVersion + ": "
                + String.join(", ", missing));
        }
        return new DatabaseView(dsl, SchemaCatalog.read(connection, dialect, modlTables), schemaVersion, access.fence());
    }

    public SchemaCatalog catalog() {
        return catalog;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public Optional<String> settingsDocument(String type) {
        fence.requireServing();
        return dsl.select(SETTINGS.DATA_JSON).from(SETTINGS).where(SETTINGS.TYPE.eq(type)).fetchOptional(SETTINGS.DATA_JSON);
    }

    public boolean holdsSecretsKeyedBy(String storageSigningKey) {
        fence.requireServing();
        byte[] expected = storageSigningKey.getBytes(StandardCharsets.UTF_8);
        return dsl.select(INSTANCE_SECRETS.STORAGE_SIGNING_KEY).from(INSTANCE_SECRETS)
            .fetchOptional(INSTANCE_SECRETS.STORAGE_SIGNING_KEY)
            .filter(stored -> MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), expected))
            .isPresent();
    }

    public long count(SchemaTable table) {
        fence.requireServing();
        return dsl.fetchCount(table.table());
    }

    public void forEachRow(SchemaTable table, RowConsumer consumer) throws IOException {
        fence.requireServing();
        try (Cursor<Record> rows = dsl.select(table.fields()).from(table.table()).fetchSize(FETCH_SIZE).fetchLazy()) {
            long read = 0;
            for (Record row : rows) {
                consumer.accept(Arrays.asList(row.intoArray()));
                read++;
                if (read % FETCH_SIZE == 0) {
                    fence.requireServing();
                }
            }
        }
    }

    private static Set<String> modlTables(MigratedSchemas schemas, SchemaState state, DatabaseDialect dialect, String version) {
        if (!schemas.isNewerThanThisJar(version)) {
            return schemas.through(version).tables();
        }
        if (dialect.isExternal()) {
            throw new DatabaseToolException("The database has schema version " + version + " of a newer modl, so this version cannot"
                + " tell which tables are modl's. Use the modl-server jar of that version.");
        }
        return state.tables();
    }

    private static DatabaseToolException unfinishedRestore() {
        return new DatabaseToolException("The database holds an unfinished restore or copy; run restore again to finish it");
    }

    private static DatabaseToolException notModlDatabase() {
        return new DatabaseToolException("This is not a modl database: it has no schema history");
    }
}
