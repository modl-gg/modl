package gg.modl.backend.database;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.sqlite.SQLiteException;

public final class ConstraintViolations {
    public enum Kind {
        UNIQUE,
        FOREIGN_KEY,
        CHECK,
        NOT_NULL,
        TOO_LONG,
        RETRYABLE,
        INTERRUPTED,
        DISK_FULL,
        OTHER
    }

    private static final int SQLITE_PRIMARY_CODE_MASK = 0xff;
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_INTERRUPT = 9;
    private static final int SQLITE_FULL = 13;
    private static final Map<Integer, Kind> SQLITE_EXTENDED_CODES = Map.of(
        2067, Kind.UNIQUE,
        1555, Kind.UNIQUE,
        787, Kind.FOREIGN_KEY,
        275, Kind.CHECK,
        1299, Kind.NOT_NULL
    );
    private static final Map<Integer, Kind> MYSQL_VENDOR_CODES = Map.ofEntries(
        Map.entry(1062, Kind.UNIQUE),
        Map.entry(1586, Kind.UNIQUE),
        Map.entry(1406, Kind.TOO_LONG),
        Map.entry(3819, Kind.CHECK),
        Map.entry(4025, Kind.CHECK),
        Map.entry(1451, Kind.FOREIGN_KEY),
        Map.entry(1452, Kind.FOREIGN_KEY),
        Map.entry(1216, Kind.FOREIGN_KEY),
        Map.entry(1217, Kind.FOREIGN_KEY),
        Map.entry(1048, Kind.NOT_NULL),
        Map.entry(1364, Kind.NOT_NULL),
        Map.entry(1213, Kind.RETRYABLE),
        Map.entry(1205, Kind.RETRYABLE),
        Map.entry(1317, Kind.INTERRUPTED),
        Map.entry(3024, Kind.INTERRUPTED),
        Map.entry(1969, Kind.INTERRUPTED),
        Map.entry(1114, Kind.DISK_FULL),
        Map.entry(1021, Kind.DISK_FULL)
    );
    private static final Map<String, Kind> POSTGRESQL_STATES = Map.of(
        "23505", Kind.UNIQUE,
        "23503", Kind.FOREIGN_KEY,
        "23514", Kind.CHECK,
        "23502", Kind.NOT_NULL,
        "22001", Kind.TOO_LONG,
        "40P01", Kind.RETRYABLE,
        "40001", Kind.RETRYABLE,
        "55P03", Kind.RETRYABLE,
        "57014", Kind.INTERRUPTED,
        "53100", Kind.DISK_FULL
    );
    private static final Set<Kind> UNTRANSLATED = Set.of(Kind.CHECK, Kind.NOT_NULL, Kind.OTHER);
    private static final Set<Kind> DATA_REJECTIONS = Set.of(Kind.UNIQUE, Kind.FOREIGN_KEY, Kind.CHECK, Kind.NOT_NULL, Kind.TOO_LONG);

    private final DatabaseDialect dialect;
    private final DiskFullNotice diskFull;

    public ConstraintViolations(DatabaseDialect dialect, DiskFullNotice diskFull) {
        this.dialect = dialect;
        this.diskFull = diskFull;
    }

    public Kind classify(Throwable failure) {
        return firstSqlException(failure).map(this::classify).orElse(Kind.OTHER);
    }

    public boolean isRetryable(Throwable failure) {
        return failure instanceof ConcurrentUpdateException || classify(failure) == Kind.RETRYABLE;
    }

    public boolean isDataRejection(RuntimeException failure) {
        return failure instanceof DuplicateRowException
            || failure instanceof ReferenceViolationException
            || failure instanceof ValueTooLongException
            || unstorableTextIn(failure).isPresent()
            || DATA_REJECTIONS.contains(classify(failure));
    }

    public boolean isBusy(RuntimeException failure) {
        return failure instanceof DatabaseBusyException || isRetryable(failure);
    }

    public boolean isDiskFull(RuntimeException failure) {
        return failure instanceof DatabaseUnavailableException unavailable
            ? unavailable.reason() == DatabaseUnavailableException.Reason.DISK_FULL
            : classify(failure) == Kind.DISK_FULL;
    }

    public boolean isUnavailable(RuntimeException failure) {
        return failure instanceof DatabaseUnavailableException || isBusy(failure);
    }

    public RuntimeException translate(RuntimeException failure) {
        if (isKernelException(failure)) {
            return failure;
        }
        Optional<UnstorableTextException> unstorable = unstorableTextIn(failure);
        if (unstorable.isPresent()) {
            return unstorable.get();
        }
        Kind kind = classify(failure);
        if (UNTRANSLATED.contains(kind)) {
            return failure;
        }
        return switch (kind) {
            case UNIQUE -> new DuplicateRowException("The row conflicts with an existing row", failure);
            case FOREIGN_KEY -> new ReferenceViolationException("The row refers to a row that does not exist", failure);
            case TOO_LONG -> new ValueTooLongException("A value is longer than its column allows", failure);
            case DISK_FULL -> diskFullFailure(failure);
            case INTERRUPTED -> new DatabaseBusyException("The database statement ran too long and was cancelled",
                DatabaseBusyException.LANE_RETRY_AFTER, failure);
            default -> new DatabaseBusyException("The database is busy; try again shortly",
                DatabaseBusyException.POOL_RETRY_AFTER, failure);
        };
    }

    private RuntimeException diskFullFailure(RuntimeException failure) {
        diskFull.report();
        return new DatabaseUnavailableException(DatabaseUnavailableException.Reason.DISK_FULL, failure);
    }

    private static boolean isKernelException(RuntimeException failure) {
        return failure instanceof DuplicateRowException
            || failure instanceof ReferenceViolationException
            || failure instanceof ValueTooLongException
            || failure instanceof DatabaseBusyException
            || failure instanceof DatabaseUnavailableException
            || failure instanceof ConcurrentUpdateException
            || failure instanceof StaleClientVersionException;
    }

    private Kind classify(SQLException exception) {
        return switch (dialect) {
            case SQLITE -> classifySqlite(exception);
            case MYSQL, MARIADB -> MYSQL_VENDOR_CODES.getOrDefault(exception.getErrorCode(), Kind.OTHER);
            case POSTGRESQL -> exception.getSQLState() == null
                ? Kind.OTHER
                : POSTGRESQL_STATES.getOrDefault(exception.getSQLState(), Kind.OTHER);
        };
    }

    private static Kind classifySqlite(SQLException exception) {
        int code = exception instanceof SQLiteException sqlite ? sqlite.getResultCode().code : exception.getErrorCode();
        Kind extended = SQLITE_EXTENDED_CODES.get(code);
        if (extended != null) {
            return extended;
        }
        return switch (code & SQLITE_PRIMARY_CODE_MASK) {
            case SQLITE_BUSY -> Kind.RETRYABLE;
            case SQLITE_INTERRUPT -> Kind.INTERRUPTED;
            case SQLITE_FULL -> Kind.DISK_FULL;
            default -> Kind.OTHER;
        };
    }

    private static Optional<UnstorableTextException> unstorableTextIn(Throwable failure) {
        return firstInChain(failure, UnstorableTextException.class);
    }

    private static Optional<SQLException> firstSqlException(Throwable failure) {
        return firstInChain(failure, SQLException.class);
    }

    private static <T extends Throwable> Optional<T> firstInChain(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return Optional.of(type.cast(current));
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }
}
