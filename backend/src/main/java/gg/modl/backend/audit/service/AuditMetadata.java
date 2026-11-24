package gg.modl.backend.audit.service;

public final class AuditMetadata {
    static final String PUNISHMENT_ID = "punishmentId";
    static final String PLAYER_ID = "playerId";
    public static final String PLAYER_NAME = "playerName";
    public static final String PUNISHMENT_TYPE = "punishmentType";
    static final String STAFF_ID = "staffId";
    static final String STAFF_USERNAME = "staffUsername";
    static final String REASON = "reason";
    static final String DURATION = "duration";
    static final String CAN_ROLLBACK = "canRollback";
    static final String ROLLBACK_REASON = "rollbackReason";
    static final String BULK_ROLLBACK = "bulkRollback";
    static final String PARDON_REASON = "pardonReason";
    static final String BULK_PARDON = "bulkPardon";
    static final String NEW_DURATION = "newDurationMs";
    static final String BULK_DURATION_CHANGE = "bulkDurationChange";

    private AuditMetadata() {
    }
}
