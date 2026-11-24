package gg.modl.backend.database.sql;

public record SchemaColumn(String name, ColumnType type, int maxLength) {
    static final int UNLIMITED = 0;

    public boolean limitsLength() {
        return maxLength > UNLIMITED;
    }
}
