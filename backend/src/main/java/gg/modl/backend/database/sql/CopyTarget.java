package gg.modl.backend.database.sql;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.DatabaseTargetException.Problem;
import gg.modl.backend.database.sql.TargetInspection.Contents;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;

public final class CopyTarget implements AutoCloseable {
    private static final String BUILT_IN_VERSION = "SQLite";

    private final LockedDatabase locked;
    private final SqlDatabaseProperties database;

    private CopyTarget(LockedDatabase locked, SqlDatabaseProperties database) {
        this.locked = locked;
        this.database = database;
    }

    public static CopyTarget open(SqlDatabaseProperties database, DataDirectoryProperties dataDirectory) {
        if (database.dialect().isExternal()) {
            requireReachable(database);
        }
        try {
            return new CopyTarget(LockedDatabase.lockBesideRunningServer(database, dataDirectory), database);
        } catch (DatabaseInUseException e) {
            throw new DatabaseTargetException(Problem.IN_USE, FailureDescriptions.describe(e), e);
        } catch (RuntimeException e) {
            throw new DatabaseTargetException(Problem.FAILED, "The database could not be opened: " + FailureDescriptions.describe(e), e);
        }
    }

    public TargetInspection inspect(String storageSigningKey) {
        Contents contents = contents(storageSigningKey);
        if (!database.dialect().isExternal()) {
            return new TargetInspection(BUILT_IN_VERSION, Optional.empty(), List.of(), List.of(), contents);
        }
        DatabaseDialect dialect = database.dialect();
        DatabaseAccess access = locked.access();
        try (Connection connection = ConnectionCheck.connect(database)) {
            DatabaseMetaData metadata = connection.getMetaData();
            Optional<String> unsupported = ServerVersionCheck.problem(dialect, metadata);
            List<String> missing = unsupported.isPresent() ? List.of() : new PrivilegeProbe(access.statements(), dialect).missing(connection);
            List<DurabilityWarning> warnings = new DurabilityAdvisor(dialect, access.queries(connection, dialect), new OperatorNotice())
                .warnings();
            return new TargetInspection(ServerVersionCheck.describe(metadata), unsupported, missing, warnings, contents);
        } catch (ConnectFailedException e) {
            throw unreachable(e);
        } catch (SQLException e) {
            throw new DatabaseTargetException(Problem.FAILED, "The database could not be checked: " + FailureDescriptions.describe(e), e);
        }
    }

    public RestoreTarget target() {
        return locked.target();
    }

    public Map<String, Long> countRowsByTable() {
        try {
            return locked.source().readConsistently(view -> view.catalog().tables().stream()
                .collect(Collectors.toUnmodifiableMap(SchemaTable::name, view::count)));
        } catch (IOException e) {
            throw new UncheckedIOException("The copied database could not be counted", e);
        }
    }

    public void clearDeparture() {
        DatabaseAccess access = locked.access();
        DataSource connections;
        if (database.dialect().isExternal()) {
            connections = access.tracked(ExternalSessions.migration(DriverDataSource.of(database), database.dialect()));
        } else {
            access.prepareSqlite();
            connections = access.tracked(access.sqliteConnections(access.databaseFile()).migration());
        }
        DepartureMarks.clear(connections, database.dialect());
    }

    @Override
    public void close() {
        locked.close();
    }

    private Contents contents(String storageSigningKey) {
        RestoreTarget target = locked.target();
        try {
            if (target.modlTables().isEmpty()) {
                return Contents.EMPTY;
            }
            if (target.holdsUnfinishedRestore()) {
                return Contents.UNFINISHED_COPY;
            }
            return locked.source().readConsistently(view -> view.holdsSecretsKeyedBy(storageSigningKey))
                ? Contents.COPY_OF_THIS_INSTALLATION
                : Contents.OTHER_DATA;
        } catch (IOException | DatabaseToolException e) {
            throw new DatabaseTargetException(Problem.FAILED, "The database's tables could not be read: "
                + FailureDescriptions.describe(e), e);
        }
    }

    private static void requireReachable(SqlDatabaseProperties database) {
        try {
            ConnectionCheck.connect(database).close();
        } catch (ConnectFailedException e) {
            throw unreachable(e);
        } catch (SQLException e) {
            throw new DatabaseTargetException(Problem.UNREACHABLE, FailureDescriptions.describe(e), e);
        }
    }

    private static DatabaseTargetException unreachable(ConnectFailedException failure) {
        return new DatabaseTargetException(failure.signInRefused() ? Problem.SIGN_IN_FAILED : Problem.UNREACHABLE,
            failure.description(), failure);
    }
}
