package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.LocalFileException;
import com.alessiodp.libby.download.RetryPolicy;
import com.alessiodp.libby.download.TransferListener;
import com.alessiodp.libby.logging.Logger;
import gg.modl.minecraft.core.util.Hex;
import gg.modl.minecraft.core.util.PluginLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class EmbeddedPayload {
    static final String LIBRARY_DIRECTORY = "lib";
    static final String VERSION_FILE = "version.txt";

    private final PayloadSource source;
    private final Path libraryDirectory;
    private final ServerLibraryDownloader downloader;
    private final ArtifactDownloader extractor;
    private final PluginLogger logger;

    EmbeddedPayload(PayloadSource source, Path libraryDirectory, PluginLogger logger) {
        this(source, libraryDirectory, MavenCentralDownloader.create(logger), logger);
    }

    EmbeddedPayload(PayloadSource source, Path libraryDirectory, ServerLibraryDownloader downloader, PluginLogger logger) {
        this.source = source;
        this.libraryDirectory = libraryDirectory;
        this.downloader = downloader;
        this.extractor = new ArtifactDownloader(new Logger(new DebugLogAdapter(logger)), RetryPolicy.of(1, 0));
        this.logger = logger;
    }

    String version() throws IOException {
        return readText(VERSION_FILE).trim();
    }

    Set<String> libraryFileNames() throws IOException {
        return fileNames(index().all());
    }

    InstalledPayload install(DownloadProgress progress, BooleanSupplier cancelled) throws IOException {
        return install(progress, cancelled, Collections.<String>emptySet());
    }

    InstalledPayload install(DownloadProgress progress, BooleanSupplier cancelled, Set<String> retainedFileNames)
            throws IOException {
        String version = version();
        ServerLibraryIndex index = index();
        Files.createDirectories(libraryDirectory);

        int extracted = 0;
        for (ServerLibrary library : index.packed()) {
            if (library.isInstalledAt(installed(library))) continue;
            extract(library);
            extracted++;
        }
        List<CentralLibrary> missing = missingCentralLibraries(index);
        if (!missing.isEmpty()) {
            progress.begin(version, missing.size(), totalSize(missing));
            downloader.download(missing, libraryDirectory, progress, cancelled);
        }
        removeStaleFiles(index.all(), retainedFileNames);

        List<Path> jars = new ArrayList<>();
        for (ServerLibrary library : index.all()) jars.add(installed(library));
        return new InstalledPayload(version, Collections.unmodifiableList(jars), extracted, missing.size());
    }

    private List<CentralLibrary> missingCentralLibraries(ServerLibraryIndex index) throws IOException {
        List<CentralLibrary> missing = new ArrayList<>();
        for (CentralLibrary library : index.central()) {
            if (!library.getFile().isInstalledAt(installed(library.getFile()))) missing.add(library);
        }
        return missing;
    }

    private Path installed(ServerLibrary library) {
        return libraryDirectory.resolve(library.getFileName());
    }

    private void extract(ServerLibrary library) throws IOException {
        URL packed = required(LIBRARY_DIRECTORY + "/" + library.getFileName());
        try {
            extractor.download(library.getFileName(), Collections.singletonList(packed.toString()),
                    Hex.decode(library.getSha256()), library.getSize(), installed(library),
                    TransferListener.NONE);
        } catch (LocalFileException unwritable) {
            throw unwritable;
        } catch (IOException corrupt) {
            throw new IOException(source.displayName() + " is corrupt: " + library.getFileName() + " does not match "
                    + ServerLibraryIndex.FILE_NAME + " (" + corrupt.getMessage() + ").", corrupt);
        }
    }

    void pruneLibrariesExcept(Set<String> keptFileNames) throws IOException {
        removeStaleFiles(Collections.<ServerLibrary>emptyList(), keptFileNames);
    }

    private ServerLibraryIndex index() throws IOException {
        return ServerLibraryIndex.parse(readText(ServerLibraryIndex.FILE_NAME));
    }

    private static Set<String> fileNames(List<ServerLibrary> libraries) {
        Set<String> names = new HashSet<>();
        for (ServerLibrary library : libraries) names.add(library.getFileName());
        return names;
    }

    private void removeStaleFiles(List<ServerLibrary> libraries, Set<String> retainedFileNames) throws IOException {
        Set<String> expected = fileNames(libraries);
        expected.addAll(retainedFileNames);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(libraryDirectory)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && !expected.contains(file.getFileName().toString())) deleteQuietly(file);
            }
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException inUse) {
            logger.debug("Could not remove the stale server library " + file + ": " + inUse.getMessage());
        }
    }

    private String readText(String name) throws IOException {
        URLConnection connection = required(name).openConnection();
        connection.setUseCaches(false);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    private URL required(String name) throws IOException {
        URL location = source.locate(name);
        if (location == null) {
            throw new IOException(source.displayName() + " has no " + name + ".");
        }
        return location;
    }

    private static long totalSize(List<CentralLibrary> libraries) {
        long total = 0;
        for (CentralLibrary library : libraries) total += library.getFile().getSize();
        return total;
    }
}
