package gg.modl.backend.hostedimport.archive;

import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;

public record ArchiveManifest(
    String exportId,
    @Nullable Instant createdAt,
    Source source,
    EntryRecord server,
    List<CollectionRecord> collections,
    List<Exclusion> excludedCollections,
    String keyPrefix,
    List<String> publicBaseUrls,
    List<StoredObjectRecord> objects,
    List<SkippedObject> skippedObjects,
    long uncompressedBytes
) {
    public ArchiveManifest {
        collections = List.copyOf(collections);
        excludedCollections = List.copyOf(excludedCollections);
        publicBaseUrls = List.copyOf(publicBaseUrls);
        objects = List.copyOf(objects);
        skippedObjects = List.copyOf(skippedObjects);
    }

    public long collectionBytes() {
        return collections.stream().mapToLong(CollectionRecord::size).sum();
    }

    public long storageBytes() {
        return objects.stream().mapToLong(StoredObjectRecord::size).sum();
    }

    public record Source(String backendVersion, @Nullable String serverName, @Nullable String subdomain,
                         @Nullable String panelDomain, @Nullable String defaultPanelDomain) {
    }

    public record EntryRecord(String path, long size, String sha256) {
    }

    public record CollectionRecord(String name, String path, long documents, long omittedDocuments, long size, String sha256) {
    }

    public record Exclusion(String name, String reason, long approximateDocuments) {
    }

    public record StoredObjectRecord(String key, long size, String contentType, String sha256) {
        public String path() {
            return STORAGE_PREFIX + key;
        }
    }

    public record SkippedObject(String key, String reason) {
    }

    public static final String STORAGE_PREFIX = "storage/";
}
