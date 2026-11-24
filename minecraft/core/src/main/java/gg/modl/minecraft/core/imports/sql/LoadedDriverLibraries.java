package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.api.LibraryRecord;
import org.jspecify.annotations.Nullable;

public final class LoadedDriverLibraries implements DriverLibraries {
    private volatile @Nullable DriverLibraries loaded;

    public void loaded(DriverLibraries libraries) {
        loaded = libraries;
    }

    @Override
    public ClassLoader isolatedLoader(String loaderId, LibraryRecord library) {
        DriverLibraries libraries = loaded;
        if (libraries == null) throw new IllegalStateException("The runtime libraries have not been loaded");
        return libraries.isolatedLoader(loaderId, library);
    }

    @Override
    public void release(String loaderId) {
        DriverLibraries libraries = loaded;
        if (libraries != null) libraries.release(loaderId);
    }
}
