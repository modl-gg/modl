package gg.modl.backend.databasemove.service;

import gg.modl.backend.database.sql.CopyTarget;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

final class OpenedTarget implements AutoCloseable {
    private final DatabaseTestResult result;
    private final @Nullable CopyTarget target;

    private OpenedTarget(DatabaseTestResult result, @Nullable CopyTarget target) {
        this.result = result;
        this.target = target;
    }

    static OpenedTarget ready(DatabaseTestResult result, CopyTarget target) {
        return new OpenedTarget(result, target);
    }

    static OpenedTarget refused(DatabaseTestResult result) {
        return new OpenedTarget(result, null);
    }

    DatabaseTestResult result() {
        return result;
    }

    Optional<CopyTarget> target() {
        return Optional.ofNullable(target);
    }

    @Override
    public void close() {
        if (target != null) {
            target.close();
        }
    }
}
