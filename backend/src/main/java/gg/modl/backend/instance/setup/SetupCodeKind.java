package gg.modl.backend.instance.setup;

public enum SetupCodeKind {
    SETUP("Setup code", "setup code", "when setup is finished", true),
    OWNER_SIGN_IN("Owner sign-in code", "owner sign-in code", "when the owner signs in", false);

    private final String consoleLabel;
    private final String fileLabel;
    private final String fileDeletedWhen;
    private final boolean offersConfigFallback;

    SetupCodeKind(String consoleLabel, String fileLabel, String fileDeletedWhen, boolean offersConfigFallback) {
        this.consoleLabel = consoleLabel;
        this.fileLabel = fileLabel;
        this.fileDeletedWhen = fileDeletedWhen;
        this.offersConfigFallback = offersConfigFallback;
    }

    String consoleLabel() {
        return consoleLabel;
    }

    String fileLabel() {
        return fileLabel;
    }

    String fileDeletedWhen() {
        return fileDeletedWhen;
    }

    boolean offersConfigFallback() {
        return offersConfigFallback;
    }
}
