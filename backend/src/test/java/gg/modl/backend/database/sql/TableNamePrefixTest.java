package gg.modl.backend.database.sql;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.jooq.DefaultSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.ForeignKey;
import org.jooq.Index;
import org.jooq.Table;
import org.junit.jupiter.api.Test;

class TableNamePrefixTest {
    static final String PREFIX = "modl_";
    private static final List<Path> MIGRATION_FOLDERS = List.of(
        Path.of("src", "main", "resources", "db", "migration"),
        Path.of("src", "test", "resources", "db", "test-migration"));
    private static final List<Path> JAVA_SOURCES = List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));
    private static final Pattern MIGRATION_NAME = Pattern.compile(
        "\\b(?:CREATE TABLE|ALTER TABLE|DROP TABLE|REFERENCES|CONSTRAINT|INDEX|ON|INTO|FROM|JOIN|UPDATE|RENAME TO|KEY)"
            + "(?:\\s+IF(?:\\s+NOT)?\\s+EXISTS)?\\s+[`\"]?([a-z][a-z0-9_]*)");
    private static final Pattern SQL_TABLE_REFERENCE = Pattern.compile(
        "(?:\\b(?:FROM|INTO|UPDATE|JOIN|TABLE|REFERENCES|ON|EXISTS)|(?i:\\binsert into))\\s+(?:\\\\?[`\"])?(\\w+)");

    @Test
    void everyModelTableIndexAndForeignKeyCarriesThePrefix() {
        Set<String> unprefixed = new TreeSet<>();
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            Stream.concat(Stream.of(table.getName()), objectNames(table))
                .filter(name -> !name.startsWith(PREFIX))
                .forEach(unprefixed::add);
        }

        assertThat(DefaultSchema.DEFAULT_SCHEMA.getTables()).isNotEmpty();
        assertThat(unprefixed).isEmpty();
    }

    @Test
    void everyObjectTheMigrationsNameCarriesThePrefix() {
        List<Path> migrations = MIGRATION_FOLDERS.stream().flatMap(folder -> files(folder, ".sql")).toList();
        Set<String> unprefixed = new TreeSet<>();
        for (Path migration : migrations) {
            Matcher name = MIGRATION_NAME.matcher(read(migration));
            while (name.find()) {
                if (!name.group(1).startsWith(PREFIX)) {
                    unprefixed.add(migration + ": " + name.group(1));
                }
            }
        }

        assertThat(migrations).isNotEmpty();
        assertThat(unprefixed).isEmpty();
    }

    @Test
    void noSqlInJavaNamesAModlTableIndexOrForeignKeyWithoutThePrefix() {
        Set<String> tables = withoutPrefix(DefaultSchema.DEFAULT_SCHEMA.getTables().stream().map(Table::getName));
        Pattern objects = Pattern.compile("(?<!\\w)(" + String.join("|",
            withoutPrefix(DefaultSchema.DEFAULT_SCHEMA.getTables().stream().flatMap(TableNamePrefixTest::objectNames))) + ")(?!\\w)");
        Set<String> violations = new TreeSet<>();
        JAVA_SOURCES.stream().flatMap(folder -> files(folder, ".java")).forEach(source -> {
            for (String literal : JavaLiterals.in(read(source))) {
                Matcher reference = SQL_TABLE_REFERENCE.matcher(literal);
                while (reference.find()) {
                    if (tables.contains(reference.group(1))) {
                        violations.add(source + ": " + literal);
                    }
                }
                if (objects.matcher(literal).find()) {
                    violations.add(source + ": " + literal);
                }
            }
        });

        assertThat(violations).isEmpty();
    }

    private static Stream<String> objectNames(Table<?> table) {
        return Stream.concat(table.getIndexes().stream().map(Index::getName), table.getReferences().stream().map(ForeignKey::getName));
    }

    private static Set<String> withoutPrefix(Stream<String> names) {
        return names.map(name -> name.substring(PREFIX.length())).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Stream<Path> files(Path folder, String extension) {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(extension)).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class JavaLiterals {
        private static final String TEXT_BLOCK = "\"\"\"";

        private final String source;
        private final List<String> literals = new ArrayList<>();
        private int position;

        private JavaLiterals(String source) {
            this.source = source;
        }

        static List<String> in(String source) {
            JavaLiterals scanner = new JavaLiterals(source);
            scanner.scan();
            return scanner.literals;
        }

        private void scan() {
            while (position < source.length()) {
                if (source.startsWith(TEXT_BLOCK, position)) {
                    int end = source.indexOf(TEXT_BLOCK, position + TEXT_BLOCK.length());
                    literals.add(source.substring(position + TEXT_BLOCK.length(), end));
                    position = end + TEXT_BLOCK.length();
                } else if (source.startsWith("//", position)) {
                    position = endOf(source.indexOf('\n', position), 1);
                } else if (source.startsWith("/*", position)) {
                    position = endOf(source.indexOf("*/", position + 2), 2);
                } else if (source.charAt(position) == '"') {
                    literals.add(quoted('"'));
                } else if (source.charAt(position) == '\'') {
                    quoted('\'');
                } else {
                    position++;
                }
            }
        }

        private String quoted(char quote) {
            int start = ++position;
            while (source.charAt(position) != quote) {
                position += source.charAt(position) == '\\' ? 2 : 1;
            }
            return source.substring(start, position++);
        }

        private int endOf(int found, int length) {
            return found < 0 ? source.length() : found + length;
        }
    }
}
