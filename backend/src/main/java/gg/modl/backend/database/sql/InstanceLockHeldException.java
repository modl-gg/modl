package gg.modl.backend.database.sql;

import org.jspecify.annotations.Nullable;

final class InstanceLockHeldException extends IllegalStateException {
    InstanceLockHeldException(String message) {
        super(message);
    }

    InstanceLockHeldException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
