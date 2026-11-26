package gg.modl.minecraft.core.boot;

import com.alessiodp.libby.Library;
import com.alessiodp.libby.LibraryManager;
import com.alessiodp.libby.classloader.IsolatedClassLoader;
import com.alessiodp.libby.logging.LogLevel;
import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.api.LibraryRelocation;
import gg.modl.minecraft.core.Libraries;
import gg.modl.minecraft.core.imports.sql.DriverLibraries;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeLibraryLoader implements DriverLibraries {
    private final LibraryManager libraryManager;
    private final PlatformType platform;
    private final ClassLoader pluginClassLoader;
    private final IsolatedDrivers drivers;
    private final Map<String, IsolatedClassLoader> isolated = new ConcurrentHashMap<>();

    public RuntimeLibraryLoader(LibraryManager libraryManager, PlatformType platform, ClassLoader pluginClassLoader) {
        this.libraryManager = libraryManager;
        this.platform = platform;
        this.pluginClassLoader = pluginClassLoader;
        this.drivers = IsolatedDrivers.read(libraryManager.getLogger());
    }

    public void loadAll() {
        libraryManager.setLogLevel(LogLevel.WARN);
        libraryManager.addMavenCentral();
        List<Library> retained = new ArrayList<>();
        for (LibraryRecord record : Libraries.forPlatform(platform)) retained.add(load(record));
        for (LibraryRecord driver : JdbcDriver.libraries()) retained.add(pinned(driver).build());
        libraryManager.retainOnly(retained);
    }

    Library toLibrary(LibraryRecord record) {
        Library.Builder builder = pinned(record);
        for (LibraryRelocation relocation : record.getRelocations()) {
            builder.relocate(relocation.getPattern(), relocation.getRelocatedPattern());
        }
        if (record.isBundled()) builder.url(bundledResource(record.getBundledDirectory() + "/" + record.getFileName()));
        return builder.build();
    }

    @Override
    public synchronized ClassLoader isolatedLoader(String loaderId, LibraryRecord record) {
        IsolatedClassLoader existing = isolated.get(loaderId);
        if (existing != null) return existing;
        Path file = libraryManager.downloadLibrary(pinned(record).build());
        IsolatedClassLoader created = new IsolatedClassLoader();
        created.addPath(file);
        isolated.put(loaderId, created);
        return created;
    }

    @Override
    public void release(String loaderId) {
        IsolatedClassLoader loader = isolated.remove(loaderId);
        if (loader != null) drivers.release(loader);
    }

    private static Library.Builder pinned(LibraryRecord record) {
        return Library.builder()
                .groupId(record.getGroupId())
                .artifactId(record.getArtifactId())
                .version(record.getVersion())
                .classifier(record.getClassifier())
                .checksumFromBase64(record.getChecksum());
    }

    private Library load(LibraryRecord record) {
        Library library = toLibrary(record);
        libraryManager.loadLibrary(library);
        return library;
    }

    private String bundledResource(String resource) {
        URL location = pluginClassLoader.getResource(resource);
        if (location == null) {
            throw new IllegalStateException("The plugin jar has no " + resource + ". Download the full modl plugin jar.");
        }
        return location.toString();
    }
}
