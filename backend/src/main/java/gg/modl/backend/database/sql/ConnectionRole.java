package gg.modl.backend.database.sql;

public enum ConnectionRole {
    POOL,
    MIGRATION,
    SNAPSHOT,
    MAINTENANCE,
    LOCK
}
