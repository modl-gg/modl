package gg.modl.minecraft.core.imports;

import com.google.gson.stream.JsonWriter;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.Removal;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.util.Constants;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ImportExportWriter implements AutoCloseable {
    private static final String MANUAL_PARDON = "MANUAL_PARDON";

    private final String sourceId;
    private final BoundedOutput output;
    private final Writer writer;
    private final JsonWriter json;
    private final String importedAt;

    ImportExportWriter(String sourceId, Path target, long maxBytes, int plannedPlayers, Instant importedAt)
            throws IOException {
        this.sourceId = sourceId;
        this.importedAt = importedAt.toString();
        this.output = new BoundedOutput(Files.newOutputStream(target), maxBytes);
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        this.json = new JsonWriter(writer);
        try {
            json.beginObject();
            json.name("metadata").beginObject();
            json.name("playerCount").value((long) plannedPlayers);
            json.endObject();
            json.name("players").beginArray();
        } catch (IOException failure) {
            writer.close();
            throw failure;
        }
    }

    boolean limitExceeded() {
        return output.exceeded;
    }

    void write(SourcePlayer player, List<SourcePunishment> punishments, boolean widened) throws IOException {
        List<SourcePunishment> notes = new ArrayList<>();
        List<SourcePunishment> enforced = new ArrayList<>();
        for (SourcePunishment punishment : punishments) {
            (punishment.getKind().isNote() ? notes : enforced).add(punishment);
        }
        json.beginObject();
        json.name("minecraftUuid").value(player.getUuid());
        writeUsernames(player.getUsernames());
        writeAddresses(player.getAddresses());
        json.name("notes").beginArray();
        for (SourcePunishment note : notes) writeNote(note);
        json.endArray();
        json.name("punishments").beginArray();
        for (SourcePunishment punishment : enforced) writePunishment(punishment, widened);
        json.endArray();
        json.endObject();
    }

    private void writeUsernames(List<SourcePlayer.Username> usernames) throws IOException {
        json.name("usernames").beginArray();
        for (SourcePlayer.Username username : usernames) {
            json.beginObject();
            json.name("username").value(username.getName());
            json.name("date").value(iso(username.getSeenAt()));
            json.endObject();
        }
        json.endArray();
    }

    private void writeAddresses(List<SourcePlayer.Address> addresses) throws IOException {
        json.name("ipList").beginArray();
        for (SourcePlayer.Address address : addresses) {
            List<Long> logins = new ArrayList<>(address.getLogins());
            Collections.sort(logins);
            json.beginObject();
            json.name("ipAddress").value(address.getIp());
            if (!logins.isEmpty()) json.name("firstLogin").value(iso(logins.get(0)));
            json.name("logins").beginArray();
            for (long login : logins) json.value(iso(login));
            json.endArray();
            json.endObject();
        }
        json.endArray();
    }

    private void writeNote(SourcePunishment note) throws IOException {
        json.beginObject();
        json.name("id").value(note.getId());
        json.name("kind").value(note.getKind().name());
        json.name("text").value(note.getReason());
        json.name("issuerName").value(textOr(note.getIssuerName(), Constants.UNKNOWN));
        json.name("date").value(iso(note.getIssuedAt()));
        json.endObject();
    }

    private void writePunishment(SourcePunishment punishment, boolean widened) throws IOException {
        String issued = iso(punishment.getIssuedAt());
        ImportedType type = ImportedType.of(punishment.getKind());
        json.beginObject();
        json.name("_id").value(punishment.getId());
        json.name("type").value(type.name());
        json.name("typeOrdinal").value((long) type.getOrdinal());
        json.name("reason").value(punishment.getReason());
        json.name("issued").value(issued);
        json.name("started").value(issued);
        String issuer = punishment.getIssuerName();
        if (issuer != null) json.name("issuerName").value(issuer);
        json.name("duration").value(punishment.getDuration());
        json.name("notes").beginArray();
        json.beginObject();
        json.name("text").value(punishment.getReason());
        if (issuer != null) json.name("issuerName").value(issuer);
        json.name("date").value(issued);
        json.endObject();
        json.endArray();
        writeRemoval(punishment.getRemoval(), punishment.getIssuedAt());
        writeData(punishment, widened);
        json.endObject();
    }

    private void writeRemoval(@Nullable Removal removal, long issuedAt) throws IOException {
        if (removal == null) return;
        Long removedAt = removal.getAt();
        json.name("modifications").beginArray();
        json.beginObject();
        json.name("type").value(MANUAL_PARDON);
        json.name("date").value(iso(removedAt != null ? removedAt : issuedAt));
        json.name("issuerName").value(removal.getBy());
        if (removal.getReason() != null) json.name("reason").value(removal.getReason());
        json.endObject();
        json.endArray();
    }

    private void writeData(SourcePunishment punishment, boolean widened) throws IOException {
        json.name("data").beginObject();
        json.name("duration").value(punishment.getDuration());
        json.name("importedFrom").value(sourceId);
        json.name("importDate").value(importedAt);
        if (punishment.getKind() != PunishmentKind.KICK) {
            if (punishment.isLifted()) json.name("active").value(false);
            else if (punishment.isInForce()) json.name("active").value(true);
        }
        if (widened && punishment.blocksAltsWhenWidened()) {
            json.name("altBlocking").value(true);
        }
        json.endObject();
    }

    void finish() throws IOException {
        json.endArray();
        json.endObject();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private static String textOr(@Nullable String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private final long maxBytes;
        private long written;
        private volatile boolean exceeded;

        private BoundedOutput(OutputStream target, long maxBytes) {
            super(target);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (exceeded || written + length > maxBytes) {
                exceeded = true;
                throw new IOException("The export exceeds the import size limit");
            }
            written += length;
        }
    }
}
