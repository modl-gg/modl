package com.alessiodp.libby;

import com.alessiodp.libby.classloader.IsolatedClassLoader;
import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.TransferListener;
import com.alessiodp.libby.logging.LogLevel;
import com.alessiodp.libby.logging.Logger;
import com.alessiodp.libby.relocation.Relocation;
import com.alessiodp.libby.relocation.RelocationHelper;
import com.alessiodp.libby.logging.adapters.LogAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A runtime dependency manager for java applications.
 * <p>
 * The library manager can resolve a dependency jar through the configured
 * Maven repositories, download it into a local cache, relocate it and then
 * load it into the classloader classpath.
 * <p>
 * Transitive dependencies for a library aren't downloaded automatically and
 * must be explicitly loaded like every other library.
 * <p>
 * It's recommended that libraries are relocated to prevent any namespace
 * conflicts with different versions of the same library bundled with other
 * java applications or maybe even bundled with the server itself.
 *
 * @see Library
 */
public abstract class LibraryManager {
    /**
     * Wrapped logger
     */
    protected final Logger logger;

    private final ArtifactDownloader downloader;

    /**
     * Directory where downloaded library jars are saved to
     */
    protected final Path saveDirectory;

    /**
     * Maven repositories used to resolve artifacts
     */
    protected final Set<String> repositories = new LinkedHashSet<>();

    /**
     * Lazily-initialized relocation helper that uses reflection to call into
     * Luck's Jar Relocator
     */
    protected RelocationHelper relocator;

    /**
     * Global isolated class loader for libraries
     */
    protected final IsolatedClassLoader globalIsolatedClassLoader = new IsolatedClassLoader();

    /**
     * Map of isolated class loaders and theirs id
     */
    protected final Map<String, IsolatedClassLoader> isolatedLibraries = new HashMap<>();
    
    /**
     * Repository resolution mode for libraries
     */
    protected RepositoryResolutionMode repositoryResolutionMode = RepositoryResolutionMode.DEFAULT;

    /**
     * Creates a new library manager.
     *
     * @param logAdapter    logging adapter
     * @param dataDirectory data directory
     * @deprecated Use {@link LibraryManager#LibraryManager(LogAdapter, Path, String)}
     */
    @Deprecated
    protected LibraryManager(@NotNull LogAdapter logAdapter, @NotNull Path dataDirectory) {
        logger = new Logger(requireNonNull(logAdapter, "logAdapter"));
        downloader = new ArtifactDownloader(logger);
        saveDirectory = requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().resolve("lib");
    }

    /**
     * Creates a new library manager.
     *
     * @param logAdapter    logging adapter
     * @param dataDirectory data directory
     * @param directoryName download directory name
     */
    protected LibraryManager(@NotNull LogAdapter logAdapter, @NotNull Path dataDirectory, @NotNull String directoryName) {
        logger = new Logger(requireNonNull(logAdapter, "logAdapter"));
        downloader = new ArtifactDownloader(logger);
        saveDirectory = requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().resolve(requireNonNull(directoryName, "directoryName"));
    }

    /**
     * Adds a file to the classloader classpath.
     *
     * @param file the file to add
     */
    protected abstract void addToClasspath(@NotNull Path file);

    /**
     * Adds a file to the isolated class loader
     *
     * @param library the library to add
     * @param file    the file to add
     */
    protected void addToIsolatedClasspath(@NotNull Library library, @NotNull Path file) {
        IsolatedClassLoader classLoader;
        String loaderId = library.getLoaderId();
        if (loaderId != null) {
            classLoader = isolatedLibraries.computeIfAbsent(loaderId, s -> new IsolatedClassLoader());
        } else {
            classLoader = globalIsolatedClassLoader;
        }
        classLoader.addPath(file);
    }
    
    /**
     * Get the global isolated class loader for libraries
     *
     * @return the isolated class loader
     */
    @NotNull
    public IsolatedClassLoader getGlobalIsolatedClassLoader() {
        return globalIsolatedClassLoader;
    }
    
    /**
     * Get the isolated class loader of the library
     *
     * @param loaderId the id of the loader
     * @return the isolated class loader associated with the provided id
     */
    @Nullable
    public IsolatedClassLoader getIsolatedClassLoaderById(@NotNull String loaderId) {
        return isolatedLibraries.get(loaderId);
    }

    /**
     * Gets the logging level for this library manager.
     *
     * @return log level
     */
    @NotNull
    public LogLevel getLogLevel() {
        return logger.getLevel();
    }

    /**
     * Sets the logging level for this library manager.
     * <p>
     * By setting this value, the library manager's logger will not log any
     * messages with a level less severe than the configured level. This can be
     * useful for silencing the download and relocation logging.
     * <p>
     * Setting this value to {@link LogLevel#WARN} would silence informational
     * logging but still print important things like invalid checksum warnings.
     *
     * @param level the log level to set
     */
    public void setLogLevel(@NotNull LogLevel level) {
        logger.setLevel(level);
    }

    /**
     * Gets the logger of this library manager.
     *
     * @return the logger
     */
    @NotNull
    public Logger getLogger() {
        return logger;
    }

    /**
     * Gets the currently added repositories used to resolve artifacts.
     * <p>
     * For each library this list is traversed to download artifacts after the
     * direct download URLs have been attempted.
     *
     * @return current repositories
     */
    @NotNull
    public Collection<String> getRepositories() {
        List<String> urls;
        synchronized (repositories) {
            urls = new LinkedList<>(repositories);
        }

        return Collections.unmodifiableList(urls);
    }

    /**
     * Adds a repository URL to this library manager.
     * <p>
     * Artifacts will be resolved using this repository when attempts to locate
     * the artifact through previously added repositories are all unsuccessful.
     *
     * @param url repository URL to add
     */
    public void addRepository(@NotNull String url) {
        String repo = requireNonNull(url, "url").endsWith("/") ? url : url + '/';
        synchronized (repositories) {
            repositories.add(repo);
        }
    }

    /**
     * Adds the current user's local Maven repository.
     */
    public void addMavenLocal() {
        addRepository(Paths.get(System.getProperty("user.home")).resolve(".m2/repository").toUri().toString());
    }

    /**
     * Adds the Maven Central repository.
     */
    public void addMavenCentral() {
        addRepository(Repositories.MAVEN_CENTRAL);
    }

    /**
     * Adds the Sonatype OSS repository.
     */
    public void addSonatype() {
        addRepository(Repositories.SONATYPE);
    }

    /**
     * Adds the Bintray JCenter repository.
     */
    public void addJCenter() {
        addRepository(Repositories.JCENTER);
    }

    /**
     * Adds the JitPack repository.
     */
    public void addJitPack() {
        addRepository(Repositories.JITPACK);
    }

    /**
     * Get the repository resolution mode.
     *
     * @return the repository resolution mode
     */
    public RepositoryResolutionMode getRepositoryResolutionMode() {
        return repositoryResolutionMode;
    }
    
    /**
     * Set the repository resolution mode.
     *
     * @param repositoryResolutionMode the repository resolution mode
     */
    public void setRepositoryResolutionMode(RepositoryResolutionMode repositoryResolutionMode) {
        this.repositoryResolutionMode = repositoryResolutionMode;
    }
    
    /**
     * Gets all the possible download URLs for this library. Entries are
     * ordered by direct download URLs first and then repository download URLs.
     * <br>This method also resolves SNAPSHOT artifacts URLs.
     *
     * @param library the library to resolve
     * @return download URLs
     */
    @NotNull
    public Collection<String> resolveLibrary(@NotNull Library library) {
        Set<String> urls = new LinkedHashSet<>(requireNonNull(library, "library").getUrls());
        boolean snapshot = library.isSnapshot();
        Collection<String> repos = resolveRepositories(library);

        for (String repository : repos) {
            if (snapshot) {
                String url = resolveSnapshot(repository, library);
                if (url != null)
                    urls.add(repository + url);
            } else {
                urls.add(repository + library.getPath());
            }
        }

        return Collections.unmodifiableSet(urls);
    }
    
    /**
     * Resolves the repository URLs for this library.
     *
     * @param library the library to resolve repositories for
     * @return the resolved repositories
     */
    public Collection<String> resolveRepositories(@NotNull Library library) {
        switch (getRepositoryResolutionMode()) {
            case GLOBAL_FIRST:
                return Stream.of(
                        getRepositories(),
                        library.getRepositories(),
                        library.getFallbackRepositories()
                ).flatMap(Collection::stream).collect(Collectors.toCollection(LinkedHashSet::new));
            case LIBRARY_FIRST:
                return Stream.of(
                        library.getRepositories(),
                        library.getFallbackRepositories(),
                        getRepositories()
                ).flatMap(Collection::stream).collect(Collectors.toCollection(LinkedHashSet::new));
            case DEFAULT:
            default:
                return Stream.of(
                        library.getRepositories(),
                        getRepositories(),
                        library.getFallbackRepositories()
                ).flatMap(Collection::stream).collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    /**
     * Resolves the URL of the artifact of a snapshot library.
     *
     * @param repository The repository to query for snapshot information
     * @param library    The library
     * @return The URl of the artifact of a snapshot library or null if no information could be gathered from the
     * provided repository
     */
    @Nullable
    protected String resolveSnapshot(@NotNull String repository, @NotNull Library library) {
        String mavenMetadata = repository.startsWith("file") ? "maven-metadata-local.xml" : "maven-metadata.xml";
        String url = requireNonNull(repository, "repository") + requireNonNull(library, "library").getPartialPath() + mavenMetadata;
        try {
            URLConnection connection = new URL(requireNonNull(url, "url")).openConnection();

            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", LibbyProperties.HTTP_USER_AGENT);

            try (InputStream in = connection.getInputStream()) {
                return getURLFromMetadata(in, library);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        } catch (IOException e) {
            if (e instanceof FileNotFoundException) {
                logger.debug("File not found: " + url);
            } else if (e instanceof SocketTimeoutException) {
                logger.debug("Connect timed out: " + url);
            } else if (e instanceof UnknownHostException) {
                logger.debug("Unknown host: " + url);
            } else {
                logger.debug("Unexpected IOException", e);
            }

            return null;
        }
    }

    /**
     * Gets the URL of the artifact of a snapshot library from the provided InputStream, which should be opened to the
     * library's maven-metadata.xml.
     *
     * @param inputStream The InputStream opened to the library's maven-metadata.xml
     * @param library     The library
     * @return The URl of the artifact of a snapshot library or null if no information could be gathered from the
     * provided inputStream
     * @throws IOException If any IO errors occur
     */
    @Nullable
    protected String getURLFromMetadata(@NotNull InputStream inputStream, @NotNull Library library) throws IOException {
        requireNonNull(inputStream, "inputStream");
        requireNonNull(library, "library");

        String version = library.getVersion();
        try {
            // This reads the maven-metadata.xml file and gets the snapshot info from the <snapshot> tag.
            // Example tag:
            // <snapshot>
            //     <timestamp>20220617.013635</timestamp>
            //     <buildNumber>12</buildNumber>
            // </snapshot>

            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(inputStream);
            doc.getDocumentElement().normalize();

            NodeList nodes = doc.getElementsByTagName("snapshot");
            if (nodes.getLength() == 0) {
                return null;
            }
            Node snapshot = nodes.item(0);
            if (snapshot.getNodeType() != Node.ELEMENT_NODE) {
                return null;
            }
            Node localCopyNode = ((Element) snapshot).getElementsByTagName("localCopy").item(0);
            if (localCopyNode == null || localCopyNode.getNodeType() != Node.ELEMENT_NODE) {
                // Resolve with snapshot number instead of base name
                Node timestampNode = ((Element) snapshot).getElementsByTagName("timestamp").item(0);
                if (timestampNode == null || timestampNode.getNodeType() != Node.ELEMENT_NODE) {
                    return null;
                }
                Node buildNumberNode = ((Element) snapshot).getElementsByTagName("buildNumber").item(0);
                if (buildNumberNode == null || buildNumberNode.getNodeType() != Node.ELEMENT_NODE) {
                    return null;
                }
                Node timestampChild = timestampNode.getFirstChild();
                if (timestampChild == null || timestampChild.getNodeType() != Node.TEXT_NODE) {
                    return null;
                }
                Node buildNumberChild = buildNumberNode.getFirstChild();
                if (buildNumberChild == null || buildNumberChild.getNodeType() != Node.TEXT_NODE) {
                    return null;
                }
                String timestamp = timestampChild.getNodeValue();
                String buildNumber = buildNumberChild.getNodeValue();

                version = library.getVersion();
                // Call .substring(...) only on versions ending in "-SNAPSHOT".
                // It should never happen that a snapshot version doesn't end in "-SNAPSHOT", but better be sure
                if (version.endsWith("-SNAPSHOT")) {
                    version = version.substring(0, version.length() - "-SNAPSHOT".length());
                }

                version = version + '-' + timestamp + '-' + buildNumber;
            }
        } catch (ParserConfigurationException | SAXException e) {
            logger.debug("Invalid maven-metadata.xml", e);
            return null;
        }
        return Util.craftPath(library.getPartialPath(), library.getArtifactId(), version, library.getClassifier());
    }

    @NotNull
    public Path downloadLibrary(@NotNull Library library) {
        Path file = saveDirectory.resolve(requireNonNull(library, "library").getPath());
        if (library.isSnapshot() || !isIntact(file, library)) {
            fetch(library, file);
        }
        return library.hasRelocations() ? relocate(file, library.getRelocatedPath(), library.getRelocations()) : file;
    }

    public void retainOnly(@NotNull Collection<Library> libraries) {
        Set<Path> retained = new HashSet<>();
        for (Library library : requireNonNull(libraries, "libraries")) {
            retain(retained, library);
        }
        for (Library tool : RelocationHelper.LIBRARIES) {
            retain(retained, tool);
        }
        if (!Files.isDirectory(saveDirectory)) {
            return;
        }
        try {
            Files.walkFileTree(saveDirectory, new UnusedFileRemover(retained));
        } catch (IOException e) {
            logger.debug("Could not remove unused libraries from " + saveDirectory, e);
        }
    }

    private void retain(Set<Path> retained, Library library) {
        retained.add(saveDirectory.resolve(library.getPath()).normalize());
        if (library.hasRelocations()) {
            retained.add(saveDirectory.resolve(library.getRelocatedPath()).normalize());
        }
    }

    private final class UnusedFileRemover extends SimpleFileVisitor<Path> {
        private final Set<Path> retained;

        private UnusedFileRemover(Set<Path> retained) {
            this.retained = retained;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!retained.contains(file.normalize())) {
                deleteQuietly(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) {
            logger.debug("Could not inspect " + file, failure);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
            if (!directory.equals(saveDirectory) && isEmptyDirectory(directory)) {
                deleteQuietly(directory);
            }
            return FileVisitResult.CONTINUE;
        }
    }

    private boolean isEmptyDirectory(Path directory) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            return !children.iterator().hasNext();
        } catch (IOException e) {
            logger.debug("Could not list " + directory, e);
            return false;
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            logger.debug("Could not remove the unused library file " + path, e);
        }
    }

    private boolean isIntact(Path file, Library library) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (!library.hasChecksum()) {
            return true;
        }
        try {
            return Arrays.equals(ArtifactDownloader.sha256(file), library.getChecksum());
        } catch (IOException e) {
            logger.debug("Could not read " + file, e);
            return false;
        }
    }

    private void fetch(Library library, Path file) {
        Collection<String> urls = resolveLibrary(library);
        if (urls.isEmpty()) {
            throw new RuntimeException("Library '" + library + "' couldn't be resolved, add a repository");
        }
        try {
            downloader.download(library.toString(), urls, library.getChecksum(), ArtifactDownloader.UNKNOWN_SIZE, file,
                    TransferListener.NONE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download library '" + library + "': " + e.getMessage(), e);
        }
    }

    /**
     * Processes the input jar and generates an output jar with the provided
     * relocation rules applied, then returns the path to the relocated jar.
     *
     * @param in          input jar
     * @param out         output jar
     * @param relocations relocations to apply
     * @return the relocated file
     * @see RelocationHelper#relocate(Path, Path, Collection)
     */
    @NotNull
    public Path relocate(@NotNull Path in, @NotNull String out, @NotNull Collection<Relocation> relocations) {
        requireNonNull(in, "in");
        requireNonNull(out, "out");
        requireNonNull(relocations, "relocations");

        Path file = saveDirectory.resolve(out);
        if (Files.exists(file)) {
            return file;
        }

        Path tmpOut = file.resolveSibling(file.getFileName() + ".tmp");
        tmpOut.toFile().deleteOnExit();

        synchronized (this) {
            if (relocator == null) {
                relocator = new RelocationHelper(this);
            }
        }

        try {
            relocator.relocate(in, tmpOut, relocations);
            Files.move(tmpOut, file);

            logger.info("Relocations applied to " + in.getFileName());

            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(tmpOut);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Loads a library jar into the classloader classpath. If the library jar
     * doesn't exist locally, it will be downloaded.
     * <p>
     * If the provided library has any relocations, they will be applied to
     * create a relocated jar and the relocated jar will be loaded instead.
     *
     * @param library the library to load
     * @see #downloadLibrary(Library)
     */
    public void loadLibrary(@NotNull Library library) {
        logger.info("Loading library " + library);
        Path file = downloadLibrary(requireNonNull(library, "library"));

        if (library.isIsolatedLoad()) {
            addToIsolatedClasspath(library, file);
        } else {
            addToClasspath(file);
        }
    }

    /**
     * Loads multiple libraries into the classloader classpath.
     *
     * @param libraries the libraries to load
     * @see #loadLibrary(Library)
     */
    public void loadLibraries(@NotNull Library... libraries) {
        for (Library library : libraries) {
            loadLibrary(library);
        }
    }
}
