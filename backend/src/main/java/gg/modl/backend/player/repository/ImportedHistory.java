package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.JsonColumns;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;

final class ImportedHistory {
    static final String IMPORTED_ID = "importedId";

    private final Map<String, Set<String>> punishmentIds;
    private final Map<String, Set<String>> noteIds;

    private ImportedHistory(Map<String, Set<String>> punishmentIds, Map<String, Set<String>> noteIds) {
        this.punishmentIds = punishmentIds;
        this.noteIds = noteIds;
    }

    static ImportedHistory load(DSLContext dsl, JsonColumns json, Collection<String> playerIds) {
        Map<String, Set<String>> punishmentIds = new HashMap<>();
        Map<String, Set<String>> noteIds = new HashMap<>();
        for (List<String> chunk : InClauseChunks.<String>of(playerIds)) {
            dsl.select(PUNISHMENTS.PLAYER_ID, PUNISHMENTS.ID, PUNISHMENTS.EXTRA_JSON)
                .from(PUNISHMENTS)
                .where(PUNISHMENTS.PLAYER_ID.in(chunk))
                .fetch()
                .forEach(row -> {
                    Set<String> known = punishmentIds.computeIfAbsent(row.value1(), ignored -> new HashSet<>());
                    known.add(row.value2());
                    if (json.readMap(row.value3()).get(IMPORTED_ID) instanceof String importedId) {
                        known.add(importedId);
                    }
                });
            dsl.select(PLAYER_NOTES.PLAYER_ID, PLAYER_NOTES.ID)
                .from(PLAYER_NOTES)
                .where(PLAYER_NOTES.PLAYER_ID.in(chunk))
                .fetch()
                .forEach(row -> noteIds.computeIfAbsent(row.value1(), ignored -> new HashSet<>()).add(row.value2()));
        }
        return new ImportedHistory(punishmentIds, noteIds);
    }

    boolean rememberPunishment(String playerId, String sourceId) {
        return punishmentIds.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(sourceId);
    }

    boolean rememberNote(String playerId, String noteId) {
        return noteIds.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(noteId);
    }
}
