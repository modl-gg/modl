package gg.modl.minecraft.core;

import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.core.boot.PlatformType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Libraries {
    public static final String BUNDLED_DIRECTORY = "modl-libs";
    public static final LibraryRecord PROTO = LibraryVersions.PROTO.bundledIn(BUNDLED_DIRECTORY);
    public static final LibraryRecord IMPORT_HSQLDB = LibraryVersions.IMPORT_HSQLDB;
    public static final LibraryRecord IMPORT_H2_LEGACY = LibraryVersions.IMPORT_H2_LEGACY;
    public static final LibraryRecord IMPORT_SQLITE = LibraryVersions.IMPORT_SQLITE;
    public static final LibraryRecord IMPORT_MARIADB = LibraryVersions.IMPORT_MARIADB;
    public static final LibraryRecord IMPORT_POSTGRESQL = LibraryVersions.IMPORT_POSTGRESQL;

    private static final List<LibraryRecord> EVERY_PLATFORM = Collections.unmodifiableList(Arrays.asList(
            LibraryVersions.PROTOBUF_JAVA,
            PROTO,
            LibraryVersions.SNAKEYAML,
            LibraryVersions.GSON,
            LibraryVersions.JAVA_WEBSOCKET,
            LibraryVersions.LAMP_COMMON,
            LibraryVersions.LAMP_BRIGADIER,
            LibraryVersions.SLF4J_API
    ));

    private static final Map<PlatformType, List<LibraryRecord>> BY_PLATFORM = byPlatform();

    private Libraries() {}

    public static List<LibraryRecord> forPlatform(PlatformType platform) {
        List<LibraryRecord> libraries = BY_PLATFORM.get(platform);
        if (libraries == null) throw new IllegalStateException("No runtime libraries are declared for " + platform);
        return libraries;
    }

    private static Map<PlatformType, List<LibraryRecord>> byPlatform() {
        Map<PlatformType, List<LibraryRecord>> libraries = new EnumMap<>(PlatformType.class);
        libraries.put(PlatformType.SPIGOT, onEveryPlatformAnd(LibraryVersions.LAMP_BUKKIT, LibraryVersions.SLF4J_SIMPLE));
        libraries.put(PlatformType.BUNGEECORD, onEveryPlatformAnd(LibraryVersions.LAMP_BUNGEE));
        libraries.put(PlatformType.VELOCITY, onEveryPlatformAnd(LibraryVersions.LAMP_VELOCITY));
        return Collections.unmodifiableMap(libraries);
    }

    private static List<LibraryRecord> onEveryPlatformAnd(LibraryRecord... platformLibraries) {
        List<LibraryRecord> libraries = new ArrayList<>(EVERY_PLATFORM);
        libraries.addAll(Arrays.asList(platformLibraries));
        return Collections.unmodifiableList(libraries);
    }
}
