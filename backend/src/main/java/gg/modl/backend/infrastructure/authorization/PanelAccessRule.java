package gg.modl.backend.infrastructure.authorization;

public enum PanelAccessRule {
    REQUIRE_PERMISSION,
    PERMIT_ALL,
    PLAYER_ACCESS,
    PUNISHMENT_MODIFICATION,
    PUNISHMENT_TYPE_ACCESS,
    TICKET_WORKFLOW_ACCESS,
    APPEAL_REPLY,
    REPLAY_SHARING,
    SUPER_ADMIN
}
