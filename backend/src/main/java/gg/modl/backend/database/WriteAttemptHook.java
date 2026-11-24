package gg.modl.backend.database;

@FunctionalInterface
public interface WriteAttemptHook {
    WriteAttemptHook NONE = attempt -> {
    };

    void beforeCommit(int attempt);
}
