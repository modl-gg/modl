package gg.modl.backend.hostedimport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.json.JsonMapper;

public final class HostedArchiveFixture {
    public static final String EXPORT_ID = "5f0c7a55-0a53-4b8e-9d1c-0c8f0f0d2a11";
    public static final String PREFIX = "server_example/";
    public static final String BASE_URL = "https://cdn.modl.gg/";
    public static final List<String> COLLECTIONS = List.of("chat_logs", "command_logs", "homepagecards", "knowledgebasearticles",
        "knowledgebasecategories", "logs", "players", "replays", "settings", "staffroles", "staffs", "storage_files", "tickets");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, List<String>> lines = new LinkedHashMap<>();
    private final Map<String, byte[]> objects = new TreeMap<>();
    private final Map<String, String> declaredSha256 = new LinkedHashMap<>();
    private final List<String> extraEntries = new ArrayList<>();
    private final Map<String, Long> declaredDocuments = new LinkedHashMap<>();
    private String adminEmail = "owner@example.com";
    private int formatVersion = 1;

    public HostedArchiveFixture() {
        COLLECTIONS.forEach(name -> lines.put(name, new ArrayList<>()));
    }

    public HostedArchiveFixture line(String collection, String json) {
        lines.get(collection).add(json);
        return this;
    }

    public HostedArchiveFixture object(String relativeKey, String content) {
        objects.put(PREFIX + relativeKey, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public HostedArchiveFixture adminEmail(String email) {
        this.adminEmail = email;
        return this;
    }

    public HostedArchiveFixture formatVersion(int version) {
        this.formatVersion = version;
        return this;
    }

    public HostedArchiveFixture wrongSha256(String collection) {
        declaredSha256.put(collection, "0".repeat(64));
        return this;
    }

    public HostedArchiveFixture wrongDocumentCount(String collection, long documents) {
        declaredDocuments.put(collection, documents);
        return this;
    }

    public HostedArchiveFixture extraEntry(String name) {
        extraEntries.add(name);
        return this;
    }

    public Path write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path archive = directory.resolve(EXPORT_ID.replace("5f0c7a55", "aaaaaaaa") + ".zip");
        Map<String, Object> manifest = new LinkedHashMap<>();
        List<Map<String, Object>> collections = new ArrayList<>();
        List<Map<String, Object>> objectRecords = new ArrayList<>();
        long collectionBytes = 0;
        long documents = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            byte[] server = JSON.writeValueAsBytes(Map.of("id", "665f1c2e8b3e4a0012345678", "serverName", "Example Network",
                "subdomain", "example", "adminEmail", adminEmail, "createdAt", "2025-03-01T12:00:00Z"));
            entry(zip, "server.json", server);
            for (Map.Entry<String, List<String>> collection : lines.entrySet()) {
                StringBuilder content = new StringBuilder();
                collection.getValue().forEach(line -> content.append(line).append('\n'));
                byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
                String path = "collections/" + collection.getKey() + ".ndjson";
                entry(zip, path, bytes);
                long count = declaredDocuments.getOrDefault(collection.getKey(), (long) collection.getValue().size());
                collections.add(record(collection.getKey(), path, count, bytes, declaredSha256.get(collection.getKey())));
                collectionBytes += bytes.length;
                documents += count;
            }
            long storageBytes = 0;
            for (Map.Entry<String, byte[]> object : objects.entrySet()) {
                entry(zip, "storage/" + object.getKey(), object.getValue());
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("key", object.getKey());
                record.put("path", "storage/" + object.getKey());
                record.put("size", object.getValue().length);
                record.put("contentType", "image/png");
                record.put("sha256", sha256(object.getValue()));
                objectRecords.add(record);
                storageBytes += object.getValue().length;
            }
            for (String extra : extraEntries) {
                entry(zip, extra, new byte[] {1});
            }
            manifest.put("format", "modl-hosted-export");
            manifest.put("formatVersion", formatVersion);
            manifest.put("exportId", EXPORT_ID);
            manifest.put("createdAt", "2026-11-22T10:15:30.123Z");
            manifest.put("completedAt", "2026-11-22T10:19:02Z");
            manifest.put("source", Map.of("backendVersion", "2.3.1", "serverId", "665f1c2e8b3e4a0012345678", "serverName", "Example Network",
                "subdomain", "example", "panelDomain", "play.example.com", "defaultPanelDomain", "example.modl.gg"));
            manifest.put("server", Map.of("path", "server.json", "size", server.length, "sha256", sha256(server)));
            manifest.put("collections", collections);
            manifest.put("excludedCollections", List.of(Map.of("name", "sessions", "reason", "SESSION", "approximateDocuments", 12)));
            manifest.put("storage", Map.of("keyPrefix", PREFIX, "publicBaseUrls", List.of(BASE_URL), "objects", objectRecords,
                "skippedObjects", List.of(Map.of("key", PREFIX + "evidence/removed.png", "reason", "DELETED_DURING_EXPORT"))));
            manifest.put("totals", Map.of("documents", documents, "collectionBytes", collectionBytes, "storageObjects", objectRecords.size(),
                "storageBytes", storageBytes, "uncompressedBytes", server.length + collectionBytes + storageBytes));
            entry(zip, "manifest.json", JSON.writeValueAsBytes(manifest));
        }
        return archive;
    }

    private static Map<String, Object> record(String name, String path, long documents, byte[] bytes, String sha256) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", name);
        record.put("path", path);
        record.put("documents", documents);
        record.put("omittedDocuments", 0);
        record.put("size", bytes.length);
        record.put("sha256", sha256 != null ? sha256 : sha256(bytes));
        return record;
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        OutputStream out = zip;
        out.write(bytes);
        zip.closeEntry();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
