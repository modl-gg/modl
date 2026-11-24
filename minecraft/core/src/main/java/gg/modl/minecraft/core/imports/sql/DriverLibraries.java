package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.api.LibraryRecord;

public interface DriverLibraries {
    ClassLoader isolatedLoader(String loaderId, LibraryRecord library);

    void release(String loaderId);
}
