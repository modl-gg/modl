package gg.modl.backend.ticket.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import gg.modl.backend.infrastructure.util.CanonicalAliasIndex;
import org.jspecify.annotations.Nullable;

public enum AppealWorkflowStatus {
    OPEN("open", "Open"),
    UNDER_REVIEW("under_review", "Under Review"),
    PENDING_PLAYER_RESPONSE("pending_player_response", "Pending Player Response"),
    APPROVED("approved", "Approved"),
    REJECTED("rejected", "Rejected"),
    CLOSED("closed", "Closed");

    private final String id;
    private final String displayName;
    private static final CanonicalAliasIndex<AppealWorkflowStatus> INDEX = CanonicalAliasIndex
        .of("appeal workflow status", values(), AppealWorkflowStatus::getId)
        .alias(UNDER_REVIEW, "underreview")
        .alias(PENDING_PLAYER_RESPONSE, "pendingplayerresponse")
        .alias(APPROVED, "approve")
        .alias(APPROVED, "accepted")
        .alias(APPROVED, "accept")
        .alias(REJECTED, "reject")
        .alias(REJECTED, "dismiss")
        .alias(REJECTED, "dismissed")
        .alias(REJECTED, "denied")
        .alias(REJECTED, "deny");

    AppealWorkflowStatus(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @JsonCreator
    public static AppealWorkflowStatus fromValue(String value) {
        return fromCanonicalId(value);
    }

    public static AppealWorkflowStatus fromCanonicalId(String value) {
        return INDEX.resolve(value);
    }

    @JsonValue
    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public TicketStatus toTicketStatus() {
        return isTerminal() ? TicketStatus.CLOSED : TicketStatus.OPEN;
    }

    public boolean isTerminal() {
        return isDecision() || this == CLOSED;
    }

    public boolean isDecision() {
        return this == APPROVED || this == REJECTED;
    }

    @Nullable
    public static AppealWorkflowStatus afterLifecycleChange(@Nullable AppealWorkflowStatus current, TicketStatus status) {
        boolean settled = current != null && current.isTerminal();
        if (status.isTerminal() && !settled) {
            return CLOSED;
        }
        if (!status.isTerminal() && settled) {
            return OPEN;
        }
        return null;
    }
}
