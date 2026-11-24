package gg.modl.backend.database.selection;

import org.jspecify.annotations.Nullable;

public record PanelDatabaseChoice(
    DatabaseConnection selected,
    Adoption adoption,
    @Nullable DatabaseConnection previous,
    @Nullable DatabaseLocation copyingTo,
    @Nullable String moveToken,
    @Nullable FailedMove failure,
    @Nullable StartEnding unreported
) {
    public enum Adoption {
        PENDING,
        ACTIVE
    }

    public enum StartEnding {
        ARRIVED,
        FELL_BACK
    }

    public static PanelDatabaseChoice initial() {
        return new PanelDatabaseChoice(DatabaseConnection.builtIn(), Adoption.ACTIVE, null, null, null, null, null);
    }

    public boolean pending() {
        return adoption == Adoption.PENDING;
    }

    public boolean startedMove(String token) {
        return token.equals(moveToken);
    }

    public boolean arrived() {
        return unreported == StartEnding.ARRIVED;
    }

    public boolean settled() {
        return moveToken == null && unreported == null;
    }

    public PanelDatabaseChoice copying(DatabaseLocation target, String token) {
        return new PanelDatabaseChoice(selected, adoption, previous, target, token, null, null);
    }

    public PanelDatabaseChoice failed(DatabaseLocation target, MoveFailure reason) {
        return new PanelDatabaseChoice(selected, adoption, previous, copyingTo, moveToken, new FailedMove(target, reason), unreported);
    }

    public PanelDatabaseChoice movedTo(DatabaseConnection target) {
        return new PanelDatabaseChoice(target, Adoption.PENDING, selected, null, moveToken, null, null);
    }

    public PanelDatabaseChoice adopted() {
        return new PanelDatabaseChoice(selected, Adoption.ACTIVE, null, null, moveToken, null, StartEnding.ARRIVED);
    }

    public PanelDatabaseChoice fellBack(MoveFailure reason) {
        DatabaseConnection returnTo = previous == null ? DatabaseConnection.builtIn() : previous;
        return new PanelDatabaseChoice(returnTo, Adoption.ACTIVE, null, selected.location(), moveToken,
            new FailedMove(selected.location(), reason), StartEnding.FELL_BACK);
    }

    public PanelDatabaseChoice reported() {
        return new PanelDatabaseChoice(selected, adoption, previous, copyingTo, null, failure, null);
    }
}
