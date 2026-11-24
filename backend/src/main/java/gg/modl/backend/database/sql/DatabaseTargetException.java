package gg.modl.backend.database.sql;

import org.jspecify.annotations.Nullable;

public final class DatabaseTargetException extends RuntimeException {
    public enum Problem {
        UNREACHABLE,
        SIGN_IN_FAILED,
        IN_USE,
        FAILED
    }

    private final Problem problem;

    DatabaseTargetException(Problem problem, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }
}
