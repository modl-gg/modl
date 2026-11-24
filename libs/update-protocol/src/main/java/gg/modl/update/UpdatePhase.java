package gg.modl.update;

public enum UpdatePhase {
    STAGED,
    BACKING_UP,
    MIGRATING,
    STARTING,
    ROLLBACK;

    public boolean beforeMigration() {
        return this == STAGED || this == BACKING_UP;
    }

    public boolean mayHaveChangedDatabase() {
        return this == MIGRATING || this == STARTING || this == ROLLBACK;
    }
}
