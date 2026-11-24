package gg.modl.backend.infrastructure.util;

public final class FailureDescriptions {
    private static final String MODL_PACKAGE = "gg.modl.";

    private FailureDescriptions() {
    }

    public static String describe(Throwable failure) {
        Throwable described = failure;
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                described = current;
            }
            if (current.getClass().getName().startsWith(MODL_PACKAGE) && current.getMessage() != null) {
                return current.getMessage();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return described.getMessage() != null ? described.getMessage() : described.getClass().getSimpleName();
    }
}
