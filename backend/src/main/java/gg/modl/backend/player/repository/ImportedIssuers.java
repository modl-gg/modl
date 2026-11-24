package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.STAFF;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;

final class ImportedIssuers {
    private final Set<String> staffIds;

    private ImportedIssuers(Set<String> staffIds) {
        this.staffIds = staffIds;
    }

    static ImportedIssuers resolve(DSLContext dsl, Collection<Player> players) {
        Set<String> referenced = players.stream()
            .flatMap(ImportedIssuers::referencedIds)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return new ImportedIssuers(new HashSet<>(InClauseChunks.collect(referenced,
            chunk -> dsl.select(STAFF.ID).from(STAFF).where(STAFF.ID.in(chunk)).fetch(STAFF.ID))));
    }

    NoteEntry playerNote(NoteEntry note) {
        return new NoteEntry(note.getId(), note.getText(), note.getDate(),
            name(note.getIssuerId(), note.getIssuerName()), staffId(note.getIssuerId()), note.getKind());
    }

    Punishment punishment(Punishment source, String id, Map<String, Object> data) {
        return new Punishment(
            id,
            source.getTypeOrdinal(),
            name(source.getIssuerId(), source.getIssuerName()),
            staffId(source.getIssuerId()),
            source.getIssuerKind(),
            source.getIssued(),
            source.getStarted(),
            source.getModifications().stream().map(this::modification).toList(),
            source.getNotes().stream().map(this::note).toList(),
            source.getEvidence().stream().map(this::evidence).toList(),
            List.copyOf(source.getAttachedTicketIds()),
            data);
    }

    private PunishmentModification modification(PunishmentModification source) {
        return new PunishmentModification(source.id(), source.type(), source.date(),
            name(source.issuerId(), source.issuerName()), staffId(source.issuerId()), source.reason(),
            source.effectiveDuration(), source.appealTicketId(), source.data());
    }

    private PunishmentNote note(PunishmentNote source) {
        return new PunishmentNote(source.id(), source.text(), source.date(),
            name(source.issuerId(), source.issuerName()), staffId(source.issuerId()));
    }

    private PunishmentEvidence evidence(PunishmentEvidence source) {
        return new PunishmentEvidence(source.text(), source.url(), source.type(),
            name(source.uploadedById(), source.uploadedBy()), staffId(source.uploadedById()),
            source.uploadedAt(), source.fileName(), source.fileType(), source.fileSize());
    }

    @Nullable
    private String staffId(@Nullable String id) {
        return id != null && staffIds.contains(id) ? id : null;
    }

    @Nullable
    private String name(@Nullable String id, @Nullable String name) {
        if (staffId(id) != null || (name != null && !name.isBlank())) {
            return name;
        }
        return PlayerColumns.fitOrNull(PUNISHMENTS.ISSUER_NAME, id);
    }

    private static Stream<String> referencedIds(Player player) {
        Stream<String> notes = player.getNotes().stream().map(NoteEntry::getIssuerId);
        Stream<String> punishments = player.getPunishments().stream().flatMap(ImportedIssuers::referencedIds);
        return Stream.concat(notes, punishments);
    }

    private static Stream<String> referencedIds(Punishment punishment) {
        return Stream.of(
                Stream.of(punishment.getIssuerId()),
                punishment.getModifications().stream().map(PunishmentModification::issuerId),
                punishment.getNotes().stream().map(PunishmentNote::issuerId),
                punishment.getEvidence().stream().map(PunishmentEvidence::uploadedById))
            .flatMap(ids -> ids);
    }
}
