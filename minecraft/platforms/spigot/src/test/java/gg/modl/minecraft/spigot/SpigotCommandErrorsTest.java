package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.command.error.CommandError;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import org.junit.jupiter.api.Test;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.exception.EmptyEntitySelectorException;
import revxrsal.commands.bukkit.exception.InvalidPlayerException;
import revxrsal.commands.bukkit.exception.InvalidWorldException;
import revxrsal.commands.bukkit.exception.MalformedEntitySelectorException;
import revxrsal.commands.bukkit.exception.MissingLocationParameterException;
import revxrsal.commands.bukkit.exception.MissingLocationParameterException.MissingAxis;
import revxrsal.commands.bukkit.exception.MoreThanOneEntityException;
import revxrsal.commands.bukkit.exception.NonPlayerEntitiesException;
import revxrsal.commands.bukkit.exception.SenderNotConsoleException;
import revxrsal.commands.bukkit.exception.SenderNotPlayerException;
import revxrsal.commands.exception.ThrowableFromCommand;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpigotCommandErrorsTest {
    private final List<Throwable> failures = Arrays.asList(
            new SenderNotPlayerException(),
            new SenderNotConsoleException(),
            new InvalidPlayerException("x"),
            new InvalidWorldException("x"),
            new MissingLocationParameterException("x", MissingAxis.Y),
            new MalformedEntitySelectorException("x", "English detail"),
            new NonPlayerEntitiesException("x"),
            new MoreThanOneEntityException("x"),
            new EmptyEntitySelectorException("x"));

    @Test
    void theCasesCoverEveryExceptionInTheInstalledBukkitLamp() throws Exception {
        Set<String> covered = new TreeSet<>(failures.stream()
                .map(failure -> failure.getClass().getName())
                .collect(Collectors.toList()));

        assertEquals(exceptionsInBukkitLamp(), covered);
    }

    @Test
    void eachExceptionHasATranslationAndNeverEchoesTheEnglishDetail() throws IOException {
        CommandErrorCatalog catalog = new CommandErrorCatalog();
        SpigotCommandErrors.addTo(catalog);
        String english = englishLocale();

        for (Throwable failure : failures) {
            assertTrue(catalog.explains(failure.getClass()), failure.getClass().getName());
            CommandError error = catalog.errorFor(failure);
            String key = error.getLocaleKey().substring(error.getLocaleKey().indexOf('.') + 1);
            assertTrue(english.contains("\n  " + key + ": "), error.getLocaleKey());
            assertTrue(!error.getValues().containsValue("English detail"), key);
        }
    }

    private static Set<String> exceptionsInBukkitLamp() throws Exception {
        Set<String> names = new TreeSet<>();
        File jarFile = new File(BukkitLamp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith("Exception.class")) continue;
                String name = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                Class<?> type = Class.forName(name, false, BukkitLamp.class.getClassLoader());
                if (type.isAnnotationPresent(ThrowableFromCommand.class)) names.add(name);
            }
        }
        return names;
    }

    private static String englishLocale() throws IOException {
        try (InputStream input = CommandError.class.getResourceAsStream("/locale/en_US.yml")) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) text.write(buffer, 0, read);
            return new String(text.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
