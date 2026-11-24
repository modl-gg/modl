package gg.modl.minecraft.core.embedded;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

@FunctionalInterface
interface ServerLibraryDownloader {
    void download(List<CentralLibrary> libraries, Path directory, DownloadListener listener, BooleanSupplier cancelled);
}
