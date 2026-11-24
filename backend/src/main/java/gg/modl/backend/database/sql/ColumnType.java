package gg.modl.backend.database.sql;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;

public enum ColumnType {
    BOOLEAN(SQLDataType.BOOLEAN, Set.of("BOOLEAN", "BOOL", "BIT", "TINYINT")),
    INTEGER(SQLDataType.BIGINT, Set.of("INTEGER", "INT", "BIGINT", "SMALLINT", "INT2", "INT4", "INT8")),
    TEXT(SQLDataType.CLOB, Set.of("VARCHAR", "CHAR", "BPCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT")),
    BYTES(SQLDataType.BLOB, Set.of("BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "VARBINARY", "BYTEA"));

    private final DataType<?> dataType;
    private final Set<String> declaredNames;

    ColumnType(DataType<?> dataType, Set<String> declaredNames) {
        this.dataType = dataType;
        this.declaredNames = declaredNames;
    }

    static Optional<ColumnType> ofDeclared(String declaredType) {
        String name = declaredType.toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.declaredNames.contains(name)).findFirst();
    }

    DataType<?> dataType() {
        return dataType;
    }
}
