package gg.modl.backend.storage.local;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
final class LocalObjectTree {
    private static final char HIDDEN_PREFIX = '.';

    private LocalObjectTree() {
    }

    static Stream<Entry> regularFiles(Path start) {
        Walker walker = new Walker(start);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(walker, Spliterator.ORDERED | Spliterator.NONNULL), false)
            .onClose(walker::close);
    }

    record Entry(Path path, BasicFileAttributes attributes) {
    }

    private static final class Walker implements Iterator<Entry>, Closeable {
        private final Deque<Path> directories = new ArrayDeque<>();
        private @Nullable DirectoryStream<Path> openDirectory;
        private @Nullable Iterator<Path> openEntries;
        private @Nullable Entry next;

        private Walker(Path start) {
            directories.push(start);
        }

        @Override
        public boolean hasNext() {
            return peek() != null;
        }

        @Override
        public Entry next() {
            Entry current = peek();
            if (current == null) {
                throw new NoSuchElementException();
            }
            next = null;
            return current;
        }

        @Override
        public void close() {
            closeOpenDirectory();
        }

        private @Nullable Entry peek() {
            Entry found = next;
            while (found == null) {
                Iterator<Path> entries = entriesWithMore();
                if (entries == null) {
                    return null;
                }
                Path candidate = nextCandidate(entries);
                found = candidate == null ? null : describe(candidate);
            }
            next = found;
            return found;
        }

        private @Nullable Iterator<Path> entriesWithMore() {
            Iterator<Path> entries = openEntries;
            while (entries == null || !hasMoreEntries(entries)) {
                closeOpenDirectory();
                if (directories.isEmpty()) {
                    return null;
                }
                entries = open(directories.pop());
            }
            return entries;
        }

        private static boolean hasMoreEntries(Iterator<Path> entries) {
            try {
                return entries.hasNext();
            } catch (DirectoryIteratorException e) {
                log.debug("Skipping the rest of a storage directory that could not be read", e);
                return false;
            }
        }

        private static @Nullable Path nextCandidate(Iterator<Path> entries) {
            try {
                return entries.next();
            } catch (DirectoryIteratorException e) {
                log.debug("Skipping a storage directory entry that could not be read", e);
                return null;
            }
        }

        @SuppressWarnings("StreamResourceLeak")
        private @Nullable Iterator<Path> open(Path directory) {
            try {
                DirectoryStream<Path> listing = Files.newDirectoryStream(directory);
                openDirectory = listing;
                openEntries = listing.iterator();
                return openEntries;
            } catch (IOException e) {
                log.debug("Skipping storage directory {} that could not be opened", directory, e);
                return null;
            }
        }

        private @Nullable Entry describe(Path path) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    if (!isHidden(path)) {
                        directories.push(path);
                    }
                    return null;
                }
                return attributes.isRegularFile() ? new Entry(path, attributes) : null;
            } catch (NoSuchFileException e) {
                return null;
            } catch (IOException e) {
                log.debug("Skipping storage entry {} that could not be read", path, e);
                return null;
            }
        }

        private void closeOpenDirectory() {
            if (openDirectory == null) {
                return;
            }
            try {
                openDirectory.close();
            } catch (IOException e) {
                log.debug("Failed to close a storage directory listing", e);
            }
            openDirectory = null;
            openEntries = null;
        }

        private static boolean isHidden(Path path) {
            Path name = path.getFileName();
            return name != null && !name.toString().isEmpty() && name.toString().charAt(0) == HIDDEN_PREFIX;
        }
    }
}
