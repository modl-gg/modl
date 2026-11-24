package gg.modl.backend.hostedimport.model;

import java.util.EnumSet;
import java.util.Optional;

public enum ImportPhase {
    VERIFY,
    BEGIN,
    CONFIGURATION,
    FILES,
    PLAYERS,
    LINKS,
    TICKETS,
    REPLAYS,
    CHAT_LOGS,
    COMMAND_LOGS,
    AUDIT_LOGS,
    ACCESS,
    SUBSCRIPTIONS,
    FINISH;

    public Optional<ImportPhase> next() {
        return EnumSet.range(this, FINISH).stream().skip(1).findFirst();
    }

    public boolean grantsAccessFromHere() {
        return compareTo(ACCESS) >= 0;
    }

    public Optional<IssueCode> unfinishedIssue() {
        return switch (this) {
            case ACCESS -> Optional.of(IssueCode.STAFF_ACCESS_NOT_RESTORED);
            case SUBSCRIPTIONS -> Optional.of(IssueCode.SUBSCRIPTIONS_NOT_RESTORED);
            default -> Optional.empty();
        };
    }
}
