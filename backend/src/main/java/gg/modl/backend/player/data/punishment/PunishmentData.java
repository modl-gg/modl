package gg.modl.backend.player.data.punishment;

import java.util.Set;

public final class PunishmentData {
    public static final String STATUS = "status";
    public static final String DURATION = "duration";
    public static final String REASON = "reason";
    public static final String SEVERITY = "severity";
    public static final String OFFENSE_LEVEL = "offenseLevel";
    public static final String ENFORCEMENT_CATEGORY = "enforcementCategory";
    public static final String ALT_BLOCKING = "altBlocking";
    public static final String WIPE_AFTER_EXPIRY = "wipeAfterExpiry";
    public static final String SILENT = "silent";
    public static final String STAT_WIPE_COMPLETED = "statWipeCompleted";
    public static final String STAT_WIPE_COMPLETED_AT = "statWipeCompletedAt";
    public static final String PENDING_ACKNOWLEDGEMENT = "pendingAcknowledgement";
    public static final String LINKED_BAN_ID = "linkedBanId";
    public static final String LINKED_BAN_PARENT_UUID = "linkedBanParentUuid";
    public static final String BLOCKED_NAME = "blockedName";
    public static final String BLOCKED_SKIN = "blockedSkin";
    public static final String APPEAL_OUTCOME = "appealOutcome";
    public static final String APPEAL_TICKET_ID = "appealTicketId";
    public static final Set<String> STRUCTURED_KEYS = Set.of(
        STATUS, DURATION, REASON, SEVERITY, OFFENSE_LEVEL, ENFORCEMENT_CATEGORY, ALT_BLOCKING, WIPE_AFTER_EXPIRY,
        STAT_WIPE_COMPLETED, STAT_WIPE_COMPLETED_AT, LINKED_BAN_ID, LINKED_BAN_PARENT_UUID, BLOCKED_NAME, BLOCKED_SKIN,
        APPEAL_OUTCOME, APPEAL_TICKET_ID
    );

    private PunishmentData() {}
}
