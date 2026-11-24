package gg.modl.minecraft.core.imports.sql;

public final class DatabasePassword {
    private final String value;

    private DatabasePassword(String value) {
        this.value = value;
    }

    public static DatabasePassword of(String value) {
        return new DatabasePassword(value);
    }

    String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "DatabasePassword(redacted)";
    }
}
