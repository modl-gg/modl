package gg.modl.backend.database.selection;

public record FailedMove(DatabaseLocation target, MoveFailure failure) {
}
