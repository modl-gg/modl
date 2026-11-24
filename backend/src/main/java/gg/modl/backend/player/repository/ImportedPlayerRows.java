package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;

import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerUsernamesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

final class ImportedPlayerRows {
    private final DSLContext dsl;
    private final Upserts upserts;

    ImportedPlayerRows(DSLContext dsl, Upserts upserts) {
        this.dsl = dsl;
        this.upserts = upserts;
    }

    void mergeUsername(PlayerUsernamesRecord row) {
        Map<Field<?>, Object> widened = new HashMap<>();
        Field<Instant> firstSeen = PLAYER_USERNAMES.FIRST_SEEN_AT;
        if (row.getFirstSeenAt() != null) {
            widened.put(firstSeen, DSL.when(firstSeen.isNull().or(firstSeen.gt(row.getFirstSeenAt())),
                DSL.val(row.getFirstSeenAt(), firstSeen)).otherwise(firstSeen));
        }
        widened.put(PLAYER_USERNAMES.LAST_SEEN_AT, later(PLAYER_USERNAMES.LAST_SEEN_AT, row.getLastSeenAt()));
        upserts.onPrimaryKey(PLAYER_USERNAMES, row, widened);
    }

    void mergeAddress(PlayerIpsRecord row, ImportedAddressRetention retention) {
        Field<Instant> firstSeen = PLAYER_IPS.FIRST_SEEN_AT;
        Field<Long> loginCount = PLAYER_IPS.LOGIN_COUNT;
        Map<Field<?>, Object> widened = new HashMap<>();
        widened.put(firstSeen, DSL.when(firstSeen.gt(row.getFirstSeenAt()), DSL.val(row.getFirstSeenAt(), firstSeen)).otherwise(firstSeen));
        widened.put(PLAYER_IPS.LAST_SEEN_AT, later(PLAYER_IPS.LAST_SEEN_AT, row.getLastSeenAt()));
        widened.put(loginCount,
            DSL.when(loginCount.lt(row.getLoginCount()), DSL.val(row.getLoginCount(), loginCount)).otherwise(loginCount));
        Instant retainedUntil = retention.retainedUntil();
        if (retainedUntil != null) {
            row.setRetainedUntilAt(retainedUntil);
            widened.put(PLAYER_IPS.RETAINED_UNTIL_AT, retainedUntil);
        }
        upserts.onPrimaryKey(PLAYER_IPS, row, widened);
    }

    void mergeHeader(String playerId, PlayersRecord imported) {
        Map<Field<?>, Object> widened = new HashMap<>();
        widened.put(PLAYERS.FIRST_JOIN_AT, earlier(PLAYERS.FIRST_JOIN_AT, imported.getFirstJoinAt()));
        widened.put(PLAYERS.LAST_LOGIN_AT, laterOrSame(PLAYERS.LAST_LOGIN_AT, imported.getLastLoginAt()));
        widened.put(PLAYERS.LAST_LOGOUT_AT, laterOrSame(PLAYERS.LAST_LOGOUT_AT, imported.getLastLogoutAt()));
        widened.put(PLAYERS.CREATED_AT, earlier(PLAYERS.CREATED_AT, imported.getCreatedAt()));
        Field<Long> playtime = PLAYERS.TOTAL_PLAYTIME_SECONDS;
        widened.put(playtime, DSL.when(playtime.lt(imported.getTotalPlaytimeSeconds()),
            DSL.val(imported.getTotalPlaytimeSeconds(), playtime)).otherwise(playtime));
        dsl.update(PLAYERS)
            .set(widened)
            .where(PLAYERS.ID.eq(playerId))
            .execute();
    }

    private static Field<Instant> earlier(Field<Instant> field, @Nullable Instant value) {
        if (value == null) {
            return field;
        }
        return DSL.when(field.isNull().or(field.gt(value)), DSL.val(value, field)).otherwise(field);
    }

    private static Field<Instant> laterOrSame(Field<Instant> field, @Nullable Instant value) {
        if (value == null) {
            return field;
        }
        return DSL.when(field.isNull().or(field.lt(value)), DSL.val(value, field)).otherwise(field);
    }

    void nameUnknownPlayer(String playerId, String username) {
        dsl.update(PLAYERS)
            .set(PLAYERS.USERNAME, PlayerColumns.storedUsername(username))
            .set(PLAYERS.USERNAME_LOWER, PlayerColumns.fit(PLAYERS.USERNAME_LOWER, PlayerColumns.usernameKey(username)))
            .where(PLAYERS.ID.eq(playerId))
            .and(PLAYERS.USERNAME.eq(PlayerColumns.UNKNOWN_USERNAME))
            .execute();
    }

    private static Field<Instant> later(Field<Instant> field, Instant value) {
        return DSL.when(field.lt(value), DSL.val(value, field)).otherwise(field);
    }
}
