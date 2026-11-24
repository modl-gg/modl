package gg.modl.backend.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class ConstraintViolationsDiskFullTest {
    private final OperatorNotice notice = mock(OperatorNotice.class);
    private final ConstraintViolations violations = new ConstraintViolations(DatabaseDialect.SQLITE, new DiskFullNotice(notice));

    @Test
    void aFullDiskIsUnavailableWithItsOwnCodeAndOneNotice() {
        RuntimeException first = violations.translate(diskFull());
        RuntimeException second = violations.translate(diskFull());

        DatabaseUnavailableException unavailable = assertInstanceOf(DatabaseUnavailableException.class, first);
        assertEquals("DATABASE_DISK_FULL", unavailable.code());
        assertInstanceOf(DatabaseUnavailableException.class, second);
        verify(notice, times(1)).publish(DiskFullNotice.MESSAGE);
    }

    private static DataAccessException diskFull() {
        return new DataAccessException("write failed", new SQLiteException("database or disk is full", SQLiteErrorCode.SQLITE_FULL));
    }
}
