package gg.modl.backend.database.selection;

public record MoveFailure(String code, String message, String detail) {
    public String describe() {
        return detail.isEmpty() ? message : message + " (" + detail + ")";
    }
}
