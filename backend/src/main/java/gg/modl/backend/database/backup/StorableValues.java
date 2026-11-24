package gg.modl.backend.database.backup;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.sql.DatabaseView;
import gg.modl.backend.database.sql.SchemaColumn;
import gg.modl.backend.database.sql.SchemaTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class StorableValues {
    private static final int MEDIUMTEXT_BYTES = 16_777_215;
    private static final int MAX_BYTES_PER_CHARACTER = 3;
    private static final int REPORTED_PROBLEMS = 20;

    private final DatabaseDialect dialect;
    private final Map<String, Long> problems = new TreeMap<>();

    private StorableValues(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    static void require(DatabaseView view, DatabaseDialect dialect) throws IOException {
        StorableValues values = new StorableValues(dialect);
        for (SchemaTable table : view.catalog().tables()) {
            view.forEachRow(table, row -> {
                for (int index = 0; index < row.size(); index++) {
                    if (row.get(index) instanceof String text) {
                        values.check(table, table.columns().get(index), text);
                    }
                }
            });
        }
        values.requireNoProblems();
    }

    private void check(SchemaTable table, SchemaColumn column, String text) {
        problemWith(column, text).ifPresent(problem ->
            problems.merge(table.name() + "." + column.name() + " " + problem, 1L, Long::sum));
    }

    private Optional<String> problemWith(SchemaColumn column, String text) {
        if (column.limitsLength() && text.codePointCount(0, text.length()) > column.maxLength()) {
            return Optional.of("is longer than " + column.maxLength() + " characters");
        }
        if (dialect == DatabaseDialect.POSTGRESQL && !StorableText.isStorable(text)) {
            return Optional.of("contains a NUL character, which PostgreSQL cannot store");
        }
        if (dialect.isMysqlFamily() && text.length() > MEDIUMTEXT_BYTES / MAX_BYTES_PER_CHARACTER
            && text.getBytes(StandardCharsets.UTF_8).length > MEDIUMTEXT_BYTES) {
            return Optional.of("is larger than 16 MB, which MySQL and MariaDB cannot store");
        }
        return Optional.empty();
    }

    private void requireNoProblems() throws InvalidArchiveException {
        if (problems.isEmpty()) {
            return;
        }
        String offenders = problems.entrySet().stream()
            .limit(REPORTED_PROBLEMS)
            .map(problem -> problem.getKey() + " (" + problem.getValue() + " values)")
            .collect(Collectors.joining("; "));
        throw new InvalidArchiveException("The data does not fit the database, so nothing was changed: " + offenders);
    }
}
