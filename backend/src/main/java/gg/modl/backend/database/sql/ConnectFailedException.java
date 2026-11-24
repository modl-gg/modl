package gg.modl.backend.database.sql;

import org.jspecify.annotations.Nullable;

final class ConnectFailedException extends Exception {
    private final String description;
    private final boolean signInRefused;

    ConnectFailedException(String description, boolean signInRefused, @Nullable Throwable cause) {
        super(description, cause);
        this.description = description;
        this.signInRefused = signInRefused;
    }

    String description() {
        return description;
    }

    boolean signInRefused() {
        return signInRefused;
    }
}
