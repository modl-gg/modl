package gg.modl.backend.hostedimport.archive;

import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class ManifestReader {
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
        .build();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String COLLECTION_PREFIX = "collections/";
    private static final String COLLECTION_SUFFIX = ".ndjson";
    private static final String SERVER_PATH = "server.json";

    private ManifestReader() {
    }

    public static ArchiveManifest read(InputStream manifest) {
        try (JsonParser parser = MAPPER.createParser(manifest)) {
            Draft draft = new Draft();
            require(parser.nextToken() == JsonToken.START_OBJECT);
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                readField(parser, name, draft);
            }
            require(parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null);
            return draft.build();
        } catch (JacksonException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_FORMAT, e);
        }
    }

    private static void readField(JsonParser parser, String name, Draft draft) {
        switch (name) {
            case "format" -> draft.format = parser.getValueAsString();
            case "formatVersion" -> draft.formatVersion = parser.currentToken() == JsonToken.VALUE_NUMBER_INT ? parser.getIntValue() : -1;
            case "exportId" -> draft.exportId = parser.getValueAsString();
            case "createdAt" -> draft.createdAt = parser.getValueAsString();
            case "source" -> draft.source = MAPPER.readValue(parser, RawSource.class);
            case "server" -> draft.server = MAPPER.readValue(parser, RawEntry.class);
            case "collections" -> draft.collections = items(MAPPER.readValue(parser, RawCollection[].class));
            case "excludedCollections" -> draft.excluded = items(MAPPER.readValue(parser, RawExclusion[].class));
            case "storage" -> readStorage(parser, draft);
            case "totals" -> draft.totals = MAPPER.readValue(parser, RawTotals.class);
            default -> parser.skipChildren();
        }
    }

    private static void readStorage(JsonParser parser, Draft draft) {
        require(parser.currentToken() == JsonToken.START_OBJECT);
        draft.storageSeen = true;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            switch (name) {
                case "keyPrefix" -> draft.keyPrefix = parser.getValueAsString();
                case "publicBaseUrls" -> draft.publicBaseUrls = items(MAPPER.readValue(parser, String[].class));
                case "objects" -> readObjects(parser, draft);
                case "skippedObjects" -> draft.skipped = items(MAPPER.readValue(parser, RawSkipped[].class));
                default -> parser.skipChildren();
            }
        }
    }

    private static void readObjects(JsonParser parser, Draft draft) {
        require(parser.currentToken() == JsonToken.START_ARRAY);
        String previousKey = null;
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            RawObject object = MAPPER.readValue(parser, RawObject.class);
            String key = present(object.key());
            String path = present(object.path());
            String sha256 = present(object.sha256());
            require(object.size() >= 0 && object.size() <= ArchiveLimits.MAX_ARCHIVE_BYTES);
            require(path.equals(ArchiveManifest.STORAGE_PREFIX + key));
            require(SHA256.matcher(sha256).matches());
            require(previousKey == null || compareUtf8(previousKey, key) < 0);
            previousKey = key;
            draft.objects.add(new ArchiveManifest.StoredObjectRecord(key, object.size(),
                Objects.requireNonNullElse(object.contentType(), "application/octet-stream"), sha256));
            if (draft.objects.size() > ArchiveLimits.MAX_ENTRIES) {
                throw new ImportFailure(ImportReason.ARCHIVE_TOO_MANY_FILES);
            }
        }
        require(parser.currentToken() == JsonToken.END_ARRAY);
    }

    static int compareUtf8(String first, String second) {
        int i = 0;
        int j = 0;
        while (i < first.length() && j < second.length()) {
            int a = first.codePointAt(i);
            int b = second.codePointAt(j);
            if (a != b) {
                return Integer.compare(a, b);
            }
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(first.length() - i, second.length() - j);
    }

    private static <T> List<T> items(T @Nullable [] values) {
        require(values != null && Arrays.stream(values).allMatch(Objects::nonNull));
        return List.of(values);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new ImportFailure(ImportReason.ARCHIVE_FORMAT);
        }
    }

    private static <T> T present(@Nullable T value) {
        if (value == null) {
            throw new ImportFailure(ImportReason.ARCHIVE_FORMAT);
        }
        return value;
    }

    @Nullable
    private static Instant instant(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static final class Draft {
        private @Nullable String format;
        private int formatVersion = -1;
        private @Nullable String exportId;
        private @Nullable String createdAt;
        private @Nullable RawSource source;
        private @Nullable RawEntry server;
        private List<RawCollection> collections = List.of();
        private List<RawExclusion> excluded = List.of();
        private boolean storageSeen;
        private @Nullable String keyPrefix;
        private List<String> publicBaseUrls = List.of();
        private final List<ArchiveManifest.StoredObjectRecord> objects = new ArrayList<>();
        private List<RawSkipped> skipped = List.of();
        private @Nullable RawTotals totals;

        private ArchiveManifest build() {
            if (!ArchiveLimits.FORMAT.equals(format)) {
                throw new ImportFailure(ImportReason.ARCHIVE_FORMAT);
            }
            if (formatVersion != ArchiveLimits.FORMAT_VERSION) {
                throw new ImportFailure(ImportReason.ARCHIVE_VERSION);
            }
            String exportId = present(this.exportId);
            RawEntry server = present(this.server);
            RawTotals totals = present(this.totals);
            String keyPrefix = present(this.keyPrefix);
            String serverSha256 = present(server.sha256());
            String serverPath = present(server.path());
            require(!exportId.isBlank() && storageSeen);
            require(SERVER_PATH.equals(serverPath) && server.size() >= 0 && server.size() <= ArchiveLimits.MAX_SERVER_JSON_BYTES
                && SHA256.matcher(serverSha256).matches());
            require(keyPrefix.endsWith("/") && !keyPrefix.startsWith("/"));
            require(publicBaseUrls.stream().allMatch(base -> base != null && base.endsWith("/")));
            List<ArchiveManifest.CollectionRecord> checked = checkedCollections(totals);
            for (ArchiveManifest.StoredObjectRecord object : objects) {
                require(object.key().startsWith(keyPrefix) && object.key().length() > keyPrefix.length());
            }
            long storageBytes = objects.stream().mapToLong(ArchiveManifest.StoredObjectRecord::size).sum();
            long collectionBytes = checked.stream().mapToLong(ArchiveManifest.CollectionRecord::size).sum();
            require(totals.storageObjects() == objects.size() && totals.storageBytes() == storageBytes
                && totals.collectionBytes() == collectionBytes
                && totals.uncompressedBytes() == server.size() + collectionBytes + storageBytes);
            RawSource raw = source != null ? source : new RawSource(null, null, null, null, null, null);
            return new ArchiveManifest(exportId, instant(createdAt),
                new ArchiveManifest.Source(Objects.requireNonNullElse(raw.backendVersion(), "unknown"), raw.serverName(),
                    raw.subdomain(), raw.panelDomain(), raw.defaultPanelDomain()),
                new ArchiveManifest.EntryRecord(serverPath, server.size(), serverSha256),
                checked,
                excluded.stream().filter(Objects::nonNull)
                    .map(exclusion -> new ArchiveManifest.Exclusion(Objects.requireNonNullElse(exclusion.name(), ""),
                        Objects.requireNonNullElse(exclusion.reason(), "UNCLASSIFIED"), Math.max(0, exclusion.approximateDocuments())))
                    .toList(),
                keyPrefix,
                publicBaseUrls,
                objects,
                skipped.stream().filter(Objects::nonNull)
                    .map(object -> new ArchiveManifest.SkippedObject(Objects.requireNonNullElse(object.key(), ""),
                        Objects.requireNonNullElse(object.reason(), "UNKNOWN")))
                    .toList(),
                totals.uncompressedBytes());
        }

        private List<ArchiveManifest.CollectionRecord> checkedCollections(RawTotals totals) {
            Set<String> names = new HashSet<>();
            List<ArchiveManifest.CollectionRecord> checked = new ArrayList<>();
            for (RawCollection collection : collections) {
                String name = present(collection.name());
                String path = present(collection.path());
                String sha256 = present(collection.sha256());
                require(names.add(name));
                require((COLLECTION_PREFIX + name + COLLECTION_SUFFIX).equals(path));
                require(collection.documents() >= 0 && collection.omittedDocuments() >= 0 && collection.size() >= 0
                    && collection.size() <= ArchiveLimits.MAX_COLLECTION_BYTES);
                require(SHA256.matcher(sha256).matches());
                checked.add(new ArchiveManifest.CollectionRecord(name, path, collection.documents(),
                    collection.omittedDocuments(), collection.size(), sha256));
            }
            long documents = checked.stream().mapToLong(ArchiveManifest.CollectionRecord::documents).sum();
            require(totals.documents() == documents);
            return checked;
        }
    }

    private record RawSource(@Nullable String backendVersion, @Nullable String serverId, @Nullable String serverName,
                             @Nullable String subdomain, @Nullable String panelDomain, @Nullable String defaultPanelDomain) {
    }

    private record RawEntry(@Nullable String path, long size, @Nullable String sha256) {
    }

    private record RawCollection(@Nullable String name, @Nullable String path, long documents, long omittedDocuments, long size,
                                 @Nullable String sha256) {
    }

    private record RawExclusion(@Nullable String name, @Nullable String reason, long approximateDocuments) {
    }

    private record RawObject(@Nullable String key, @Nullable String path, long size, @Nullable String contentType,
                             @Nullable String sha256) {
    }

    private record RawSkipped(@Nullable String key, @Nullable String reason) {
    }

    private record RawTotals(long documents, long collectionBytes, long storageObjects, long storageBytes, long uncompressedBytes) {
    }
}
