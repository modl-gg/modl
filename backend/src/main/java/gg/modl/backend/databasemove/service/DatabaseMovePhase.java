package gg.modl.backend.databasemove.service;

public enum DatabaseMovePhase {
    CHECKING,
    PAUSING_WRITES,
    COPYING,
    VERIFYING,
    RESTARTING,
    WAITING_FOR_RESTART,
    FAILED
}
