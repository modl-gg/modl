package gg.modl.minecraft.core.command.error;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import revxrsal.commands.Lamp;
import revxrsal.commands.exception.CommandErrorException;
import revxrsal.commands.exception.CommandInvocationException;
import revxrsal.commands.exception.CooldownException;
import revxrsal.commands.exception.InputParseException;
import revxrsal.commands.exception.InvalidDecimalException;
import revxrsal.commands.exception.InvalidListSizeException;
import revxrsal.commands.exception.InvalidStringSizeException;
import revxrsal.commands.exception.InvalidUUIDException;
import revxrsal.commands.exception.SendableException;
import revxrsal.commands.exception.ThrowableFromCommand;
import revxrsal.commands.exception.TrailingInputException;
import revxrsal.commands.exception.UnknownParameterException;
import revxrsal.commands.exception.ValueNotAllowedException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardCommandErrorsTest {
    private static final List<String> LOCALES = Arrays.asList("de_DE", "en_US", "es_ES", "fr_FR", "hi_IN", "it_IT",
            "ja_JP", "nl_NL", "pt_BR", "ru_RU", "zh_CN");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_]+}");

    private final CommandErrorCatalog catalog = standardCatalog();

    @Test
    void everyExceptionTheInstalledLampCanReportHasATranslation() throws Exception {
        List<String> untranslated = new ArrayList<>();
        List<Class<? extends Throwable>> types = reportableLampExceptions();
        for (Class<? extends Throwable> type : types) {
            if (!catalog.explains(type)) untranslated.add(type.getName());
        }

        assertFalse(types.isEmpty());
        assertEquals(Collections.emptyList(), untranslated);
    }

    @Test
    void eachVariantOfAFailureHasItsOwnExistingKey() throws IOException {
        List<Throwable> failures = new ArrayList<>(Arrays.asList(
                new InvalidDecimalException("x"),
                new InvalidUUIDException("x"),
                new TrailingInputException("x"),
                new ValueNotAllowedException("x", Arrays.asList("a", "b"), false),
                new UnknownParameterException("x", false),
                new UnknownParameterException("x", true),
                new InvalidStringSizeException(2, 4, "x"),
                new InvalidStringSizeException(2, 4, "xxxxx"),
                new InvalidListSizeException(2, 4, 1, Collections.emptyList()),
                new InvalidListSizeException(2, 4, 5, Collections.emptyList()),
                new CooldownException(1_500L)));
        for (InputParseException.Cause cause : InputParseException.Cause.values()) {
            failures.add(new InputParseException(cause));
        }
        Map<String, String> english = commandErrors("en_US");

        Set<String> keys = new TreeSet<>();
        for (Throwable failure : failures) {
            CommandError error = catalog.errorFor(failure);
            String name = error.getLocaleKey().substring("command_errors.".length());
            assertTrue(english.containsKey(name), error.getLocaleKey());
            assertEquals(placeholdersIn(english.get(name)), placeholdersFor(error), name);
            keys.add(name);
        }

        assertEquals(failures.size(), keys.size());
    }

    @Test
    void everyLocaleKeepsThePlaceholdersOfEachMessage() throws IOException {
        Map<String, String> english = commandErrors("en_US");
        for (String locale : LOCALES) {
            Map<String, String> translated = commandErrors(locale);
            assertEquals(english.keySet(), translated.keySet(), locale);
            english.forEach((key, text) ->
                    assertEquals(placeholdersIn(text), placeholdersIn(translated.get(key)), locale + " " + key));
        }
    }

    private static CommandErrorCatalog standardCatalog() {
        CommandErrorCatalog catalog = new CommandErrorCatalog();
        StandardCommandErrors.addTo(catalog);
        return catalog;
    }

    private static Set<String> placeholdersFor(CommandError error) {
        Set<String> names = new TreeSet<>();
        error.getValues().keySet().forEach(name -> names.add("{" + name + "}"));
        return names;
    }

    private static Set<String> placeholdersIn(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) names.add(matcher.group());
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> commandErrors(String locale) throws IOException {
        try (InputStream input = StandardCommandErrorsTest.class.getResourceAsStream("/locale/" + locale + ".yml")) {
            Map<String, Object> root = new Yaml().load(input);
            return (Map<String, String>) root.get("command_errors");
        }
    }

    private static List<Class<? extends Throwable>> reportableLampExceptions() throws Exception {
        List<Class<? extends Throwable>> types = new ArrayList<>();
        File jarFile = new File(Lamp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith("Exception.class")) continue;
                Class<?> type = Class.forName(name.substring(0, name.length() - ".class".length()).replace('/', '.'),
                        false, Lamp.class.getClassLoader());
                if (isHandledByTheCatalog(type)) types.add(type.asSubclass(Throwable.class));
            }
        }
        return types;
    }

    private static boolean isHandledByTheCatalog(Class<?> type) {
        if (!Throwable.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) return false;
        if (!type.isAnnotationPresent(ThrowableFromCommand.class) || type == CommandInvocationException.class) {
            return false;
        }
        return !SendableException.class.isAssignableFrom(type) || CommandErrorException.class.isAssignableFrom(type);
    }
}
