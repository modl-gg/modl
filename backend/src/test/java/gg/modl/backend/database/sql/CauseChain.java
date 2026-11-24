package gg.modl.backend.database.sql;

import java.util.stream.Collectors;
import java.util.stream.Stream;

final class CauseChain {
    private CauseChain() {
    }

    static String messages(Throwable failure) {
        return Stream.iterate(failure, cause -> cause != null, Throwable::getCause)
            .map(cause -> cause.getClass().getSimpleName() + ": " + cause.getMessage())
            .collect(Collectors.joining(" <- "));
    }

    static boolean anyOfClass(Throwable failure, String simpleName) {
        return Stream.iterate(failure, cause -> cause != null, Throwable::getCause)
            .anyMatch(cause -> cause.getClass().getSimpleName().equals(simpleName));
    }
}
