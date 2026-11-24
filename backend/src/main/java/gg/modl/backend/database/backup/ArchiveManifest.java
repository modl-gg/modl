package gg.modl.backend.database.backup;

import gg.modl.backend.backup.VerifiedEntryStream;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.sql.DatabaseView;
import gg.modl.backend.database.sql.SchemaCatalog;
import gg.modl.backend.database.sql.SchemaTable;
import gg.modl.backend.update.ReleaseBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;

record ArchiveManifest(
    int format,
    ArchiveKind kind,
    Instant createdAt,
    String appVersion,
    String schemaVersion,
    List<ArchivedTable> tables,
    @Nullable String fromVersion,
    @Nullable String toVersion,
    List<String> createdTables
) {
    static final String ENTRY_NAME = "manifest.json";
    private static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;

    private static final int CURRENT_FORMAT = 1;
    private static final String FORMAT_FIELD = "format";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern CREATED_TABLE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");
    private static final JsonColumns JSON = new JsonColumns();

    ArchiveManifest {
        tables = tables == null ? List.of() : List.copyOf(tables);
        createdTables = createdTables == null ? List.of() : List.copyOf(createdTables);
    }

    static ArchiveManifest create(ArchiveKind kind, Instant createdAt, String schemaVersion, List<ArchivedTable> tables,
                                  @Nullable UpgradeLabel label) {
        String appVersion = ReleaseBuild.current().versionText();
        return new ArchiveManifest(CURRENT_FORMAT, kind, createdAt, appVersion, schemaVersion, tables,
            label == null ? null : label.fromVersion(), label == null ? null : label.toVersion(),
            label == null ? List.of() : List.copyOf(label.createdTables()));
    }

    static ArchiveManifest read(ZipFile archive) throws IOException {
        ZipEntry entry = archive.getEntry(ENTRY_NAME);
        if (entry == null) {
            throw new InvalidArchiveException("Not a modl backup: " + ENTRY_NAME + " is missing");
        }
        try (VerifiedEntryStream input = VerifiedEntryStream.openAtMost(archive, entry, MAX_MANIFEST_BYTES)) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            input.requireIntact();
            return parse(json);
        } catch (ZipException | JacksonException e) {
            throw InvalidArchiveException.unreadable(ENTRY_NAME, e);
        }
    }

    void writeTo(ZipOutputStream zip) throws IOException {
        zip.putNextEntry(new ZipEntry(ENTRY_NAME));
        zip.write(JSON.write(this).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    TransferReport report() {
        return new TransferReport(new TreeMap<>(tables.stream().collect(Collectors.toMap(ArchivedTable::name, ArchivedTable::rows))));
    }

    ArchivedTable archivedTable(String name) throws InvalidArchiveException {
        for (ArchivedTable table : tables) {
            if (table.name().equals(name)) {
                return table;
            }
        }
        throw InvalidArchiveException.damaged(ENTRY_NAME + " does not list " + name);
    }

    List<SchemaTable> tablesInArchivedOrder(DatabaseView view) throws InvalidArchiveException {
        requireDescribes(view.schemaVersion(), view.catalog());
        List<SchemaTable> ordered = new ArrayList<>();
        for (SchemaTable table : view.catalog().tables()) {
            ordered.add(archivedTable(table.name()).inArchivedOrder(table));
        }
        return ordered;
    }

    void requireDescribes(String version, SchemaCatalog catalog) throws InvalidArchiveException {
        if (!schemaVersion.equals(version)) {
            throw InvalidArchiveException.damaged(ENTRY_NAME + " lists schema version " + schemaVersion + ", the data has " + version);
        }
        Set<String> listed = tables.stream().map(ArchivedTable::name).collect(Collectors.toSet());
        Set<String> stored = catalog.tables().stream().map(SchemaTable::name).collect(Collectors.toSet());
        if (!listed.equals(stored)) {
            throw InvalidArchiveException.damaged("its tables do not match database schema version " + version);
        }
    }

    private static ArchiveManifest parse(String json) throws InvalidArchiveException {
        Object format = JSON.readMap(json).get(FORMAT_FIELD);
        if (!(format instanceof Number number) || number.intValue() != CURRENT_FORMAT) {
            throw new InvalidArchiveException("Unsupported backup format " + format);
        }
        ArchiveManifest manifest = JSON.read(json, ArchiveManifest.class);
        if (manifest == null) {
            throw InvalidArchiveException.damaged(ENTRY_NAME + " is empty");
        }
        manifest.requireValid();
        return manifest;
    }

    private void requireValid() throws InvalidArchiveException {
        if (kind == null || createdAt == null || appVersion == null || schemaVersion == null
            || !VERSION.matcher(schemaVersion).matches() || tables.isEmpty()) {
            throw InvalidArchiveException.damaged(ENTRY_NAME + " is incomplete");
        }
        for (String created : createdTables) {
            if (created == null || !CREATED_TABLE.matcher(created).matches()) {
                throw InvalidArchiveException.damaged(ENTRY_NAME + " lists an invalid created table");
            }
        }
        Set<String> names = new HashSet<>();
        for (ArchivedTable table : tables) {
            if (table.name() == null || !IDENTIFIER.matcher(table.name()).matches() || table.rows() < 0) {
                throw InvalidArchiveException.damaged(ENTRY_NAME + " lists an invalid table");
            }
            if (!names.add(table.name())) {
                throw InvalidArchiveException.damaged(ENTRY_NAME + " lists " + table.name() + " twice");
            }
        }
    }
}
