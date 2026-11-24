package gg.modl.minecraft.core;

import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.api.LibraryRelocation;
import gg.modl.minecraft.core.boot.PlatformType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibrariesTest {
    private static final String KYORI_GROUP = "net{}kyori";
    private static final LibraryRelocation PROTOBUF = new LibraryRelocation("com{}google{}protobuf", "gg{}modl{}libs{}protobuf");
    private static final LibraryRelocation GSON = new LibraryRelocation("com{}google{}gson", "gg{}modl{}libs{}gson");
    private static final LibraryRelocation BUF_VALIDATE = new LibraryRelocation("com{}buf{}validate", "gg{}modl{}libs{}bufvalidate");

    @Test
    void relocatedLibrariesUseThePackagesTheShadedPluginClassesReference() {
        assertEquals(Arrays.asList(PROTOBUF, GSON, BUF_VALIDATE), LibraryVersions.RELOCATIONS);
        Arrays.asList(LibraryVersions.PROTOBUF_JAVA, LibraryVersions.GSON, Libraries.PROTO)
                .forEach(library -> assertEquals(LibraryVersions.RELOCATIONS, library.getRelocations(), library.toString()));
    }

    @Test
    void librariesThePluginReferencesUnrelocatedStayUnrelocated() {
        Arrays.asList(LibraryVersions.SNAKEYAML, LibraryVersions.LAMP_COMMON, LibraryVersions.SLF4J_API)
                .forEach(library -> assertTrue(library.getRelocations().isEmpty(), library.toString()));
    }

    @Test
    void theProtoClassesComeFromTheBundledLibrariesOfThePluginJar() {
        assertEquals(Libraries.BUNDLED_DIRECTORY, Libraries.PROTO.getBundledDirectory());
        pinnedLibraries().stream()
                .filter(library -> library.getGroupId().startsWith("gg{}modl"))
                .forEach(library -> assertEquals("proto", library.getArtifactId(), library.toString()));
        assertTrue(everyPlatformLibrary().stream().filter(LibraryRecord::isBundled).allMatch(Libraries.PROTO::equals));
    }

    @Test
    void everyPlatformLoadsTheRelocatedLibrariesAndItsOwnLampPlatform() {
        for (PlatformType platform : PlatformType.values()) {
            List<LibraryRecord> libraries = Libraries.forPlatform(platform);
            assertTrue(libraries.containsAll(Arrays.asList(LibraryVersions.PROTOBUF_JAVA, Libraries.PROTO, LibraryVersions.GSON)),
                    platform.name());
            assertEquals(1, libraries.stream().filter(library -> library.getArtifactId().matches("lamp\\{}(bukkit|bungee|velocity)")).count(),
                    platform.name());
        }
        assertTrue(Libraries.forPlatform(PlatformType.SPIGOT).contains(LibraryVersions.LAMP_BUKKIT));
        assertTrue(Libraries.forPlatform(PlatformType.BUNGEECORD).contains(LibraryVersions.LAMP_BUNGEE));
        assertTrue(Libraries.forPlatform(PlatformType.VELOCITY).contains(LibraryVersions.LAMP_VELOCITY));
    }

    @Test
    void guavaIsNeverDownloadedBecauseEveryPlatformBundlesIt() {
        pinnedLibraries().forEach(library -> assertFalse(library.getGroupId().equals("com{}google{}guava"), library.toString()));
    }

    @Test
    void eachPlatformLoadsEveryLibraryInOneVersionSoStaleVersionsCanBeRemoved() {
        for (PlatformType platform : PlatformType.values()) {
            List<String> modules = Libraries.forPlatform(platform).stream()
                    .map(library -> library.getGroupId() + ":" + library.getArtifactId())
                    .collect(Collectors.toList());
            assertEquals(new HashSet<>(modules).size(), modules.size(), platform.name());
        }
    }

    @Test
    void shadedAndServerProvidedArtifactsAreNeverDownloaded() {
        pinnedLibraries().forEach(library -> {
            assertFalse(library.getGroupId().startsWith("dev{}simplix"), library.toString());
            assertFalse(library.getGroupId().startsWith("com{}github{}retrooper"), library.toString());
        });
    }

    @Test
    void adventureIsNeverDownloadedBecauseThePlatformOrPacketEventsProvidesIt() {
        pinnedLibraries().forEach(library -> assertFalse(library.getGroupId().startsWith(KYORI_GROUP), library.toString()));
    }

    @Test
    void relocationsCannotBeChangedAfterTheFact() {
        assertThrows(UnsupportedOperationException.class, () -> LibraryVersions.GSON.getRelocations().clear());
        assertThrows(UnsupportedOperationException.class, () -> Libraries.forPlatform(PlatformType.SPIGOT).clear());
    }

    private static Set<LibraryRecord> everyPlatformLibrary() {
        Set<LibraryRecord> libraries = new HashSet<>();
        for (PlatformType platform : PlatformType.values()) libraries.addAll(Libraries.forPlatform(platform));
        return libraries;
    }

    private static List<LibraryRecord> pinnedLibraries() {
        return Arrays.stream(LibraryVersions.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> LibraryRecord.class.equals(field.getType()))
                .map(LibrariesTest::readLibraryRecord)
                .collect(Collectors.toList());
    }

    private static LibraryRecord readLibraryRecord(Field field) {
        try {
            return (LibraryRecord) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Unable to read library record " + field.getName(), e);
        }
    }
}
