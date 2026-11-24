package gg.modl.backend.hostedimport.model;

public record RetentionPlan(
    RetentionChoice choice,
    int chatLogDays,
    int auditLogDays,
    boolean replayRetentionEnabled,
    int replayDays,
    boolean keepsIpHistory,
    long rowsNotKept,
    long replaysNotKept,
    long estimatedBytes,
    long requiredBytes
) {
    public RetentionPlan withRequiredBytes(long bytes) {
        return new RetentionPlan(choice, chatLogDays, auditLogDays, replayRetentionEnabled, replayDays, keepsIpHistory, rowsNotKept,
            replaysNotKept, estimatedBytes, bytes);
    }
}
