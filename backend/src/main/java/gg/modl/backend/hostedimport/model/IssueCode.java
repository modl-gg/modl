package gg.modl.backend.hostedimport.model;

import java.util.Arrays;
import java.util.Optional;

public enum IssueCode {
    RECORD_UNREADABLE(IssueSeverity.LOST),
    RECORD_REJECTED(IssueSeverity.LOST),
    RECORD_TOO_LARGE(IssueSeverity.LOST),
    VALUE_TRUNCATED(IssueSeverity.CHANGED),
    VALUE_DEFAULTED(IssueSeverity.CHANGED),
    ID_REMAPPED(IssueSeverity.CHANGED),
    ID_ALIASED(IssueSeverity.CHANGED),
    REFERENCE_DANGLING(IssueSeverity.INFO),
    PLAYER_INVALID_UUID(IssueSeverity.LOST),
    PLAYER_MERGED(IssueSeverity.CHANGED),
    IP_NOT_KEPT(IssueSeverity.LOST),
    IP_INVALID(IssueSeverity.LOST),
    LOG_NOT_KEPT(IssueSeverity.LOST),
    NOTIFICATION_EXPIRED(IssueSeverity.LOST),
    PUNISHMENT_WITHOUT_DATA(IssueSeverity.CHANGED),
    PUNISHMENT_ROLLBACK_ENDS(IssueSeverity.CHANGED),
    PUNISHMENT_DELETED_TYPE_ENFORCED(IssueSeverity.ACTION),
    PUNISHMENT_CATEGORY_UNKNOWN(IssueSeverity.CHANGED),
    PUNISHMENT_RESTRICTION_UNKNOWN(IssueSeverity.CHANGED),
    PUNISHMENT_STORED_NONE(IssueSeverity.INFO),
    PUNISHMENT_DURATION_SATURATED(IssueSeverity.CHANGED),
    STAT_WIPE_PENDING(IssueSeverity.ACTION),
    KICK_MARKED_DELIVERED(IssueSeverity.INFO),
    TYPE_INVALID(IssueSeverity.LOST),
    TYPE_TOMBSTONE(IssueSeverity.INFO),
    ROLE_RENAMED(IssueSeverity.CHANGED),
    PERMISSION_DROPPED(IssueSeverity.CHANGED),
    STAFF_NOT_RESTORED(IssueSeverity.ACTION),
    STAFF_ACCESS_NOT_RESTORED(IssueSeverity.ACTION),
    SUBSCRIPTIONS_NOT_RESTORED(IssueSeverity.CHANGED),
    STAFF_RENAMED(IssueSeverity.CHANGED),
    STAFF_LINK_DROPPED(IssueSeverity.CHANGED),
    SETTING_UNREADABLE(IssueSeverity.LOST),
    WEBHOOK_DISABLED(IssueSeverity.ACTION),
    AI_DISABLED(IssueSeverity.ACTION),
    SLUG_RENAMED(IssueSeverity.CHANGED),
    ARTICLE_ORPHANED(IssueSeverity.CHANGED),
    URL_UNSAFE(IssueSeverity.CHANGED),
    URL_LEFT_REMOTE(IssueSeverity.INFO),
    FILE_KEY_RENAMED(IssueSeverity.CHANGED),
    FILE_CASE_CONFLICT(IssueSeverity.CHANGED),
    FILE_TYPE_NEUTRALISED(IssueSeverity.CHANGED),
    FILE_SKIPPED_BY_EXPORT(IssueSeverity.LOST),
    FILE_CONFLICT(IssueSeverity.LOST),
    REPLAY_FILE_MISSING(IssueSeverity.CHANGED),
    COMMAND_REDACTED(IssueSeverity.CHANGED),
    COLLECTION_UNKNOWN(IssueSeverity.LOST);

    private final IssueSeverity severity;

    IssueCode(IssueSeverity severity) {
        this.severity = severity;
    }

    public IssueSeverity severity() {
        return severity;
    }

    public static Optional<IssueCode> named(String name) {
        return Arrays.stream(values()).filter(code -> code.name().equals(name)).findFirst();
    }
}
