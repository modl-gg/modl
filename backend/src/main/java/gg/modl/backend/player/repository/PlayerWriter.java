package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.Indexes;
import gg.modl.backend.database.jooq.tables.records.PlayerNotesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;
import org.jooq.Field;

final class PlayerWriter {
    private final InsertIfAbsent insertIfAbsent;
    private final Upserts upserts;
    private final ChildRows childRows;

    PlayerWriter(InsertIfAbsent insertIfAbsent, Upserts upserts, ChildRows childRows) {
        this.insertIfAbsent = insertIfAbsent;
        this.upserts = upserts;
        this.childRows = childRows;
    }

    boolean insertHeader(PlayersRecord row) {
        return insertIfAbsent.on(PLAYERS, Indexes.UQ_PLAYERS_MINECRAFT_UUID).insert(row);
    }

    void adoptUsername(String playerId, String username, Instant seenAt) {
        Map<Field<?>, Object> adoptedAgain = Map.of(
            PLAYER_USERNAMES.USERNAME, PlayerColumns.fit(PLAYER_USERNAMES.USERNAME, username),
            PLAYER_USERNAMES.LAST_SEEN_AT, seenAt);
        upserts.onPrimaryKey(PLAYER_USERNAMES, PlayerRows.username(playerId, username, seenAt, seenAt), adoptedAgain);
    }

    void appendNote(PlayerNotesRecord row) {
        childRows.append(() -> childRows.lockRow(PLAYERS, PLAYERS.ID, row.getPlayerId()),
            PLAYER_NOTES, PLAYER_NOTES.PLAYER_ID, PLAYER_NOTES.SEQ, row);
    }

    void lockInKeyOrder(Collection<String> playerIds) {
        new TreeSet<>(playerIds).forEach(playerId -> childRows.lockRow(PLAYERS, PLAYERS.ID, playerId));
    }
}
