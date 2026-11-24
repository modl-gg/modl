package gg.modl.backend.database.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CaughtTypes {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern SINGLE_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\s*;");
    private static final Pattern WILDCARD_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([\\w.]+)\\.\\*\\s*;");
    private static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(([^)]*)\\)");
    private static final Pattern MODIFIERS = Pattern.compile("\\bfinal\\b|@[\\w.]+");
    private static final String JAVA_LANG = "java.lang";

    private CaughtTypes() {
    }

    static List<Caught> in(JavaSource source) {
        List<Caught> caught = new ArrayList<>();
        Matcher clause = CATCH.matcher(source.code());
        while (clause.find()) {
            String declaration = MODIFIERS.matcher(clause.group(1)).replaceAll(" ").trim();
            String types = declaration.substring(0, declaration.lastIndexOf(' ') < 0 ? declaration.length() : declaration.lastIndexOf(' '));
            for (String type : types.split("\\|")) {
                String name = type.trim();
                caught.add(new Caught(source.location(clause.start()), name, resolve(source, name)));
            }
        }
        return caught;
    }

    private static Optional<Class<?>> resolve(JavaSource source, String name) {
        String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String nested = name.substring(head.length()).replace('.', '$');
        String packageName = first(PACKAGE, source.code()).orElse("");
        List<String> candidates = new ArrayList<>();
        all(SINGLE_IMPORT, source.code()).stream()
            .filter(imported -> imported.endsWith("." + head))
            .forEach(imported -> candidates.add(imported + nested));
        candidates.add(packageName + "." + source.simpleTypeName() + "$" + name.replace('.', '$'));
        candidates.add(packageName + "." + head + nested);
        all(WILDCARD_IMPORT, source.code()).forEach(wildcard -> candidates.add(wildcard + "." + head + nested));
        candidates.add(JAVA_LANG + "." + head + nested);
        return candidates.stream().map(CaughtTypes::load).flatMap(Optional::stream).findFirst();
    }

    private static Optional<Class<?>> load(String className) {
        try {
            return Optional.of(Class.forName(className, false, CaughtTypes.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Optional<String> first(Pattern pattern, String code) {
        Matcher match = pattern.matcher(code);
        return match.find() ? Optional.of(match.group(1)) : Optional.empty();
    }

    private static List<String> all(Pattern pattern, String code) {
        List<String> values = new ArrayList<>();
        Matcher match = pattern.matcher(code);
        while (match.find()) {
            values.add(match.group(1));
        }
        return values;
    }

    record Caught(String location, String name, Optional<Class<?>> type) {
    }
}
