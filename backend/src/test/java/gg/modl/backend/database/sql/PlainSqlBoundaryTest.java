package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.DuplicateRowException;
import gg.modl.backend.database.ReferenceViolationException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

class PlainSqlBoundaryTest {
    private static final List<JavaSource> SOURCES = JavaSource.under(Path.of("src", "main", "java"));
    private static final String DATABASE = "gg/modl/backend/database/";
    private static final String DATABASE_SQL = DATABASE + "sql/";
    private static final String INSERT_IF_ABSENT = DATABASE + "InsertIfAbsent.java";
    private static final String UPSERTS = DATABASE + "Upserts.java";
    private static final String EXCEPTION_HANDLER = "gg/modl/backend/infrastructure/exception/GlobalExceptionHandler.java";
    private static final List<Class<?>> DATABASE_EXCEPTIONS = List.of(
        SQLException.class,
        DataAccessException.class,
        org.springframework.dao.DataAccessException.class,
        DuplicateRowException.class,
        ReferenceViolationException.class,
        ConcurrentUpdateException.class);
    private static final List<Pattern> PLAIN_SQL = List.of(
        Pattern.compile("\\bDSL\\s*\\.\\s*(sql|raw)\\s*\\("),
        Pattern.compile("(?<![\\w$])(field|table|query|condition|resultQuery)\\s*\\(\\s*\""),
        Pattern.compile("\\.(fetch|fetchOne|fetchSingle|fetchOptional|fetchAny|fetchLazy|fetchMany|fetchValue|fetchStream|execute|resultQuery)\\s*\\(\\s*\""),
        Pattern.compile("\\b(createStatement|prepareStatement|prepareCall)\\s*\\("),
        Pattern.compile("\\b(JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|SimpleJdbcInsert|SimpleJdbcCall|JdbcOperations)\\b"));
    private static final Pattern ON_CONFLICT_DO_NOTHING = Pattern.compile("\\bonConflictDoNothing\\b|\\.\\s*doNothing\\s*\\(");
    private static final Pattern ON_DUPLICATE_KEY_UPDATE = Pattern.compile("\\bonDuplicateKeyUpdate\\b|\\.\\s*doUpdate\\s*\\(");
    private static final Pattern INSERT_IGNORE = Pattern.compile("\\bonDuplicateKeyIgnore\\b|(?i)\\binsert\\s+ignore\\b");
    private static final Pattern SQLITE_BACKUP = Pattern.compile(
        "(?i)\"\\s*(backup\\s+to|restore\\s+from)\\b|\\bProgressObserver\\b|org\\.sqlite\\.core\\.DB\\b|\\bgetDatabase\\s*\\(\\s*\\)\\s*\\.\\s*(backup|restore)\\s*\\(");
    private static final Pattern QUERY_TIMEOUT = Pattern.compile("\\bsetQueryTimeout\\b");
    private static final Pattern IN_CLAUSE_CHUNKS = Pattern.compile("\\bInClauseChunks\\b");
    private static final Pattern NOT_IN = Pattern.compile("\\.\\s*notIn\\s*\\(");

    @Test
    void plainSqlAppearsOnlyUnderDatabaseSql() {
        Set<String> found = new TreeSet<>();
        PLAIN_SQL.forEach(pattern -> found.addAll(matchesOutside(pattern, path -> path.startsWith(DATABASE_SQL))));

        assertEquals(Set.of(), found);
    }

    @Test
    void onConflictDoNothingIsRenderedOnlyByInsertIfAbsent() {
        assertEquals(Set.of(), matchesOutside(ON_CONFLICT_DO_NOTHING, INSERT_IF_ABSENT::equals));
    }

    @Test
    void onDuplicateKeyUpdateIsRenderedOnlyByUpserts() {
        assertEquals(Set.of(), matchesOutside(ON_DUPLICATE_KEY_UPDATE, UPSERTS::equals));
    }

    @Test
    void insertIgnoreIsNeverRendered() {
        assertEquals(Set.of(), matchesOutside(INSERT_IGNORE, path -> false));
    }

    @Test
    void noSqliteBackupStatementOrSteppedBackupApiIsUsed() {
        assertEquals(Set.of(), matchesOutside(SQLITE_BACKUP, path -> false));
    }

    @Test
    void noStatementSetsAQueryTimeout() {
        assertEquals(Set.of(), matchesOutside(QUERY_TIMEOUT, path -> false));
    }

    @Test
    void inClauseChunksIsNeverCombinedWithNotIn() {
        Set<String> combined = SOURCES.stream()
            .filter(source -> IN_CLAUSE_CHUNKS.matcher(source.code()).find() && NOT_IN.matcher(source.code()).find())
            .map(JavaSource::path)
            .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of(), combined);
    }

    @Test
    void everyCaughtExceptionTypeResolves() {
        Set<String> unresolved = SOURCES.stream()
            .flatMap(source -> CaughtTypes.in(source).stream())
            .filter(caught -> caught.type().isEmpty())
            .map(caught -> caught.location() + " " + caught.name())
            .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of(), unresolved);
    }

    @Test
    void databaseExceptionsAreCaughtOnlyByTheKernelAndTheExceptionHandler() {
        assertEquals(Set.of(), catchesOfDatabaseExceptionsOutsideTheKernel());
    }

    @Test
    void theScanIgnoresCommentsButKeepsLinesAndStrings() {
        String code = JavaSource.withoutComments("// setQueryTimeout\nString sql = \"// kept\"; /* DSL.sql( */\nint x;");

        assertEquals(3, code.lines().count());
        assertEquals(0, QUERY_TIMEOUT.matcher(code).results().count());
        assertTrue(code.contains("\"// kept\""));
        assertFalse(code.contains("DSL.sql("));
    }

    @Test
    void aMultiCatchOfASpringDataAccessSubclassIsRecognised() {
        JavaSource source = new JavaSource("gg/modl/backend/example/Example.java", """
            package gg.modl.backend.example;
            import org.springframework.dao.DuplicateKeyException;
            class Example { void run() { try { } catch (final IllegalStateException | DuplicateKeyException raced) { } } }""");

        List<Boolean> databaseExceptions = CaughtTypes.in(source).stream()
            .map(caught -> caught.type().filter(PlainSqlBoundaryTest::isDatabaseException).isPresent())
            .toList();

        assertEquals(List.of(false, true), databaseExceptions);
    }

    private static Set<String> catchesOfDatabaseExceptionsOutsideTheKernel() {
        return SOURCES.stream()
            .filter(source -> !source.path().startsWith(DATABASE) && !source.path().equals(EXCEPTION_HANDLER))
            .flatMap(source -> CaughtTypes.in(source).stream())
            .filter(caught -> caught.type().filter(PlainSqlBoundaryTest::isDatabaseException).isPresent())
            .map(caught -> caught.location() + " " + caught.name())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean isDatabaseException(Class<?> type) {
        return DATABASE_EXCEPTIONS.stream().anyMatch(databaseException -> databaseException.isAssignableFrom(type));
    }

    private static Set<String> matchesOutside(Pattern pattern, Predicate<String> allowedPath) {
        Set<String> found = new TreeSet<>();
        for (JavaSource source : SOURCES) {
            if (allowedPath.test(source.path())) {
                continue;
            }
            Matcher match = pattern.matcher(source.code());
            while (match.find()) {
                found.add(source.location(match.start()) + " " + match.group());
            }
        }
        return found;
    }
}
