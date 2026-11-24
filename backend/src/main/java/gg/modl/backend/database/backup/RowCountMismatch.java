package gg.modl.backend.database.backup;

public record RowCountMismatch(String table, long copied, long found) {
    boolean differs() {
        return copied != found;
    }

    public String describe() {
        return table + " " + found + " of " + copied + " rows";
    }
}
