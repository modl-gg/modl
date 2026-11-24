package gg.modl.backend.database.backup;

import gg.modl.backend.backup.VerifiedEntryStream;
import gg.modl.backend.database.sql.ColumnType;
import gg.modl.backend.database.sql.DatabaseView;
import gg.modl.backend.database.sql.RowConsumer;
import gg.modl.backend.database.sql.SchemaColumn;
import gg.modl.backend.database.sql.SchemaTable;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

final class RowLines {
    private static final String ENTRY_PREFIX = "tables/";
    private static final String ENTRY_SUFFIX = ".jsonl";
    private static final int MAX_VALUE_CHARACTERS = 256 * 1024 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(MAX_VALUE_CHARACTERS).build())
            .build())
        .build();

    private RowLines() {
    }

    static String entryName(String table) {
        return ENTRY_PREFIX + table + ENTRY_SUFFIX;
    }

    static long write(DatabaseView view, SchemaTable table, ZipOutputStream zip) throws IOException {
        AtomicLong written = new AtomicLong();
        zip.putNextEntry(new ZipEntry(entryName(table.name())));
        view.forEachRow(table, values -> {
            zip.write(MAPPER.writeValueAsBytes(values));
            zip.write('\n');
            written.incrementAndGet();
        });
        zip.closeEntry();
        return written.get();
    }

    static long writeNone(SchemaTable table, ZipOutputStream zip) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName(table.name())));
        zip.closeEntry();
        return 0;
    }

    static long read(ZipFile archive, SchemaTable table, RowConsumer rows) throws IOException {
        String name = entryName(table.name());
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) {
            throw new InvalidArchiveException("Backup is incomplete: " + name + " is missing");
        }
        try (VerifiedEntryStream input = VerifiedEntryStream.open(archive, entry);
             JsonParser parser = MAPPER.createParser(input)) {
            long read = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token != JsonToken.START_ARRAY) {
                    throw malformed(name);
                }
                rows.accept(readRow(parser, table.columns(), name));
                read++;
            }
            input.requireIntact();
            return read;
        } catch (ZipException | EOFException | JacksonException e) {
            throw InvalidArchiveException.unreadable(name, e);
        }
    }

    private static List<Object> readRow(JsonParser parser, List<SchemaColumn> columns, String entry) throws InvalidArchiveException {
        List<Object> values = new ArrayList<>(columns.size());
        for (SchemaColumn column : columns) {
            JsonToken token = parser.nextToken();
            if (token == null || !accepts(column.type(), token)) {
                throw malformed(entry);
            }
            values.add(token == JsonToken.VALUE_NULL ? null : value(parser, column.type()));
        }
        if (parser.nextToken() != JsonToken.END_ARRAY) {
            throw malformed(entry);
        }
        return values;
    }

    private static boolean accepts(ColumnType type, JsonToken token) {
        if (token == JsonToken.VALUE_NULL) {
            return true;
        }
        return switch (type) {
            case BOOLEAN -> token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE;
            case INTEGER -> token == JsonToken.VALUE_NUMBER_INT;
            case TEXT, BYTES -> token == JsonToken.VALUE_STRING;
        };
    }

    private static Object value(JsonParser parser, ColumnType type) {
        return switch (type) {
            case BOOLEAN -> parser.getBooleanValue();
            case INTEGER -> parser.getLongValue();
            case TEXT -> parser.getString();
            case BYTES -> parser.getBinaryValue();
        };
    }

    private static InvalidArchiveException malformed(String entry) {
        return InvalidArchiveException.damaged(entry + " holds a row that does not fit its columns");
    }
}
