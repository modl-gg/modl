package gg.modl.backend.database.sql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

record JavaSource(String path, String code) {
    private static final String JAVA_EXTENSION = ".java";

    static List<JavaSource> under(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("No source directory at " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(JAVA_EXTENSION))
                .sorted()
                .map(file -> new JavaSource(root.relativize(file).toString().replace('\\', '/'), withoutComments(read(file))))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int lineOf(int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (code.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    String location(int offset) {
        return path + ":" + lineOf(offset);
    }

    String simpleTypeName() {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - JAVA_EXTENSION.length());
    }

    static String withoutComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                index = blankUntil(source, index, source.indexOf('\n', index), code);
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = blankUntil(source, index, end < 0 ? source.length() : end + 2, code);
            } else if (source.startsWith("\"\"\"", index)) {
                index = copyLiteral(source, index, "\"\"\"", code);
            } else if (source.charAt(index) == '"' || source.charAt(index) == '\'') {
                index = copyLiteral(source, index, String.valueOf(source.charAt(index)), code);
            } else {
                code.append(source.charAt(index++));
            }
        }
        return code.toString();
    }

    private static int blankUntil(String source, int start, int end, StringBuilder code) {
        int stop = end < 0 ? source.length() : end;
        for (int index = start; index < stop; index++) {
            code.append(source.charAt(index) == '\n' ? '\n' : ' ');
        }
        return stop;
    }

    private static int copyLiteral(String source, int start, String delimiter, StringBuilder code) {
        int index = start + delimiter.length();
        code.append(delimiter);
        while (index < source.length() && !source.startsWith(delimiter, index)) {
            if (source.charAt(index) == '\\' && index + 1 < source.length()) {
                code.append(source.charAt(index++));
            }
            code.append(source.charAt(index++));
        }
        if (index < source.length()) {
            code.append(delimiter);
            index += delimiter.length();
        }
        return index;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
