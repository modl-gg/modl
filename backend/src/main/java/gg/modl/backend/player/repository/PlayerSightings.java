package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

final class PlayerSightings {
    private final DSLContext dsl;
    private final Upserts upserts;
    private final PlayerWriter writer;

    PlayerSightings(DSLContext dsl, Upserts upserts, PlayerWriter writer) {
        this.dsl = dsl;
        this.upserts = upserts;
        this.writer = writer;
    }

    LoginRecord recordLogin(LoginUpdate update) {
        String username = PlayerColumns.storedUsername(update.username());
        Optional<PlayersRecord> existing = findHeader(update.minecraftUuid());
        if (existing.isEmpty()) {
            String id = EntityIds.next();
            if (writer.insertHeader(firstLogin(id, username, update))) {
                writer.adoptUsername(id, username, update.loggedInAt());
                return withAddress(id, true, update);
            }
            existing = findHeader(update.minecraftUuid());
        }
        PlayersRecord player = existing
            .orElseThrow(() -> new ConcurrentUpdateException("The player was created by a login that is not visible yet"));
        boolean renamed = !username.equals(player.getUsername());
        updateHeader(player.getId(), username, update);
        if (renamed) {
            writer.adoptUsername(player.getId(), username, update.loggedInAt());
        }
        return withAddress(player.getId(), false, update);
    }

    private static PlayersRecord firstLogin(String id, String username, LoginUpdate update) {
        PlayersRecord row = PlayerRows.minimal(id, update.minecraftUuid(), username, update.loggedInAt());
        row.setIsOnline(true);
        row.setFirstJoinAt(update.loggedInAt());
        row.setLastLoginAt(update.loggedInAt());
        row.setLastSkinHash(skinHash(update));
        row.setLastServerName(PlayerColumns.fitOrNull(PLAYERS.LAST_SERVER_NAME, update.serverName()));
        return row;
    }

    private void updateHeader(String id, String username, LoginUpdate update) {
        Map<Field<?>, Object> changes = new HashMap<>();
        changes.put(PLAYERS.USERNAME, username);
        changes.put(PLAYERS.USERNAME_LOWER, PlayerColumns.fit(PLAYERS.USERNAME_LOWER, PlayerColumns.usernameKey(username)));
        changes.put(PLAYERS.IS_ONLINE, true);
        changes.put(PLAYERS.LAST_LOGIN_AT, update.loggedInAt());
        changes.put(PLAYERS.FIRST_JOIN_AT, DSL.when(PLAYERS.FIRST_JOIN_AT.isNull(),
            DSL.val(update.loggedInAt(), PLAYERS.FIRST_JOIN_AT)).otherwise(PLAYERS.FIRST_JOIN_AT));
        putIfPresent(changes, PLAYERS.LAST_SKIN_HASH, skinHash(update));
        putIfPresent(changes, PLAYERS.LAST_SERVER_NAME, PlayerColumns.fitOrNull(PLAYERS.LAST_SERVER_NAME, update.serverName()));
        dsl.update(PLAYERS).set(changes).where(PLAYERS.ID.eq(id)).execute();
    }

    @Nullable
    private static String skinHash(LoginUpdate update) {
        return PlayerColumns.storedSkinHash(update.skinHash());
    }

    private LoginRecord withAddress(String id, boolean created, LoginUpdate update) {
        String address = PlayerColumns.storableAddress(update.ip());
        if (address != null) {
            recordAddress(id, address, update.geo(), update.loggedInAt());
        }
        return new LoginRecord(id, created);
    }

    void recordAddress(String playerId, String address, @Nullable IpGeo geo, Instant seenAt) {
        PlayerIpsRecord row = PlayerRows.address(playerId, address, geo, seenAt, seenAt, 1L);
        Map<Field<?>, Object> seenAgain = new HashMap<>();
        seenAgain.put(PLAYER_IPS.LAST_SEEN_AT, seenAt);
        seenAgain.put(PLAYER_IPS.RETAINED_UNTIL_AT, null);
        seenAgain.put(PLAYER_IPS.LOGIN_COUNT, PLAYER_IPS.LOGIN_COUNT.plus(1L));
        if (geo != null) {
            seenAgain.put(PLAYER_IPS.IS_PROXY, geo.proxy());
            seenAgain.put(PLAYER_IPS.IS_HOSTING, geo.hosting());
            putIfPresent(seenAgain, PLAYER_IPS.COUNTRY, row.getCountry());
            putIfPresent(seenAgain, PLAYER_IPS.REGION, row.getRegion());
            putIfPresent(seenAgain, PLAYER_IPS.ASN, row.getAsn());
        }
        upserts.onPrimaryKey(PLAYER_IPS, row, seenAgain);
    }

    private static void putIfPresent(Map<Field<?>, Object> changes, Field<String> field, @Nullable String value) {
        if (value != null) {
            changes.put(field, value);
        }
    }

    private Optional<PlayersRecord> findHeader(String uuid) {
        return dsl.selectFrom(PLAYERS).where(PLAYERS.MINECRAFT_UUID.eq(PlayerColumns.uuid(uuid))).fetchOptional();
    }
}
