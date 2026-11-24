package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

final class PunishmentChildren {
    private final DSLContext dsl;
    private final PunishmentRows rows;

    PunishmentChildren(DSLContext dsl, PunishmentRows rows) {
        this.dsl = dsl;
        this.rows = rows;
    }

    List<Punishment> complete(List<PunishmentsRecord> headers) {
        List<String> ids = idsOf(headers);
        return assemble(headers, modifications(ids), notes(ids), evidence(ids), tickets(ids));
    }

    List<Punishment> forEnforcement(List<PunishmentsRecord> headers) {
        List<String> ids = idsOf(headers);
        return assemble(headers, modifications(ids), notes(ids), Map.of(), Map.of());
    }

    Punishment forDerivation(PunishmentsRecord header) {
        List<String> ids = List.of(header.getId());
        return assemble(List.of(header), modifications(ids), Map.of(), Map.of(), Map.of()).getFirst();
    }

    private List<Punishment> assemble(
        List<PunishmentsRecord> headers,
        Map<String, List<PunishmentModification>> modifications,
        Map<String, List<PunishmentNote>> notes,
        Map<String, List<PunishmentEvidence>> evidence,
        Map<String, List<String>> tickets
    ) {
        return headers.stream()
            .map(header -> rows.toPunishment(
                header,
                modifications.getOrDefault(header.getId(), List.of()),
                notes.getOrDefault(header.getId(), List.of()),
                evidence.getOrDefault(header.getId(), List.of()),
                tickets.getOrDefault(header.getId(), List.of())))
            .toList();
    }

    private Map<String, List<PunishmentModification>> modifications(List<String> ids) {
        return load(PUNISHMENT_MODIFICATIONS, PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID, PUNISHMENT_MODIFICATIONS.SEQ,
            ids, rows::toModification);
    }

    private Map<String, List<PunishmentNote>> notes(List<String> ids) {
        return load(PUNISHMENT_NOTES, PUNISHMENT_NOTES.PUNISHMENT_ID, PUNISHMENT_NOTES.SEQ, ids, rows::toNote);
    }

    private Map<String, List<PunishmentEvidence>> evidence(List<String> ids) {
        return load(PUNISHMENT_EVIDENCE, PUNISHMENT_EVIDENCE.PUNISHMENT_ID, PUNISHMENT_EVIDENCE.SEQ, ids, rows::toEvidence);
    }

    private Map<String, List<String>> tickets(List<String> ids) {
        return load(PUNISHMENT_TICKETS, PUNISHMENT_TICKETS.PUNISHMENT_ID, PUNISHMENT_TICKETS.TICKET_ID,
            ids, row -> row.get(PUNISHMENT_TICKETS.TICKET_ID));
    }

    private <R extends Record, T> Map<String, List<T>> load(Table<R> table, TableField<R, String> parent,
                                                          TableField<R, ?> position, List<String> ids, Function<R, T> mapper) {
        List<R> children = InClauseChunks.collect(ids, chunk -> dsl.selectFrom(table)
            .where(parent.in(chunk))
            .orderBy(parent, position)
            .fetch());
        return children.stream()
            .collect(Collectors.groupingBy(child -> child.get(parent), Collectors.mapping(mapper, Collectors.toList())));
    }

    private static List<String> idsOf(List<PunishmentsRecord> headers) {
        return headers.stream().map(PunishmentsRecord::getId).toList();
    }
}
