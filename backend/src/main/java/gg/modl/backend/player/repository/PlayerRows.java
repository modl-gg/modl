package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerNotesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerUsernamesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerDataView;
import gg.modl.backend.player.data.UsernameEntry;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class PlayerRows {
    private PlayerRows() {
    }

    static PlayersRecord minimal(String id, String minecraftUuid, @Nullable String username, Instant createdAt) {
        String current = username == null || username.isBlank() ? PlayerColumns.UNKNOWN_USERNAME : username;
        PlayersRecord row = new PlayersRecord();
        row.setId(TextLimits.require(PLAYERS.ID, id));
        row.setMinecraftUuid(TextLimits.require(PLAYERS.MINECRAFT_UUID, PlayerColumns.uuid(minecraftUuid)));
        row.setUsername(PlayerColumns.storedUsername(current));
        row.setUsernameLower(PlayerColumns.fit(PLAYERS.USERNAME_LOWER, PlayerColumns.usernameKey(current)));
        row.setIsOnline(false);
        row.setTotalPlaytimeSeconds(0L);
        row.setCreatedAt(createdAt);
        return row;
    }

    static PlayersRecord header(String id, Player player, JsonColumns json, Instant createdAt) {
        PlayersRecord row = minimal(id, player.getMinecraftUuid().toString(), currentUsername(player), createdAt);
        PlayerDataView data = player.data();
        row.setIsOnline(data.isOnline());
        row.setFirstJoinAt(data.firstJoin());
        row.setLastLoginAt(data.lastLogin());
        row.setLastLogoutAt(data.lastLogout());
        row.setLastServerName(PlayerColumns.fitOrNull(PLAYERS.LAST_SERVER_NAME, data.lastServer()));
        Number playtime = data.totalPlaytimeSeconds();
        row.setTotalPlaytimeSeconds(playtime == null ? 0L : Math.max(0L, playtime.longValue()));
        row.setLastSkinHash(PlayerColumns.storedSkinHash(data.lastSkinHash()));
        row.setLastLinkedUpdateAt(data.lastLinkedUpdate());
        Map<String, Object> extra = data.extra();
        row.setExtraJson(extra.isEmpty() ? null : json.write(extra));
        return row;
    }

    @Nullable
    static String currentUsername(Player player) {
        List<UsernameEntry> usernames = player.getUsernames();
        return usernames == null || usernames.isEmpty() ? null : usernames.getLast().username();
    }

    static List<PlayerUsernamesRecord> usernames(String playerId, @Nullable List<UsernameEntry> entries, Instant fallback) {
        if (entries == null) {
            return List.of();
        }
        Map<String, PlayerUsernamesRecord> byKey = new TreeMap<>();
        for (UsernameEntry entry : entries) {
            if (entry.username() != null && !entry.username().isBlank()) {
                PlayerUsernamesRecord row = username(playerId, entry.username(), entry.date(), fallback);
                byKey.merge(row.getUsernameLower(), row, PlayerRows::combineUsernames);
            }
        }
        return List.copyOf(byKey.values());
    }

    static PlayerUsernamesRecord username(String playerId, String username, @Nullable Instant seenAt, Instant fallback) {
        return new PlayerUsernamesRecord(
            TextLimits.require(PLAYER_USERNAMES.PLAYER_ID, playerId),
            PlayerColumns.usernameKey(username),
            PlayerColumns.fit(PLAYER_USERNAMES.USERNAME, username),
            seenAt,
            seenAt != null ? seenAt : fallback);
    }

    private static PlayerUsernamesRecord combineUsernames(PlayerUsernamesRecord earlier, PlayerUsernamesRecord later) {
        Instant firstSeen = earlier.getFirstSeenAt() == null ? later.getFirstSeenAt()
            : earliest(earlier.getFirstSeenAt(), Objects.requireNonNullElse(later.getFirstSeenAt(), earlier.getFirstSeenAt()));
        return new PlayerUsernamesRecord(earlier.getPlayerId(), earlier.getUsernameLower(), later.getUsername(),
            firstSeen, latest(earlier.getLastSeenAt(), later.getLastSeenAt()));
    }

    static List<PlayerIpsRecord> addresses(String playerId, @Nullable List<IPEntry> entries, Instant fallback) {
        if (entries == null) {
            return List.of();
        }
        Map<String, PlayerIpsRecord> byAddress = new TreeMap<>();
        for (IPEntry entry : entries) {
            String address = PlayerColumns.storableAddress(entry.getIpAddress());
            if (address == null) {
                continue;
            }
            Instant firstSeen = entry.getFirstLogin() != null ? entry.getFirstLogin() : fallback;
            Instant lastSeen = entry.getLastLogin() != null ? entry.getLastLogin() : firstSeen;
            IpGeo geo = new IpGeo(entry.getCountry(), entry.getRegion(), entry.getAsn(), entry.isProxy(), entry.isHosting());
            byAddress.merge(address,
                address(playerId, address, geo, firstSeen, lastSeen, Math.max(1L, entry.getLoginCount())),
                PlayerRows::combineAddresses);
        }
        return List.copyOf(byAddress.values());
    }

    static PlayerIpsRecord address(String playerId, String address, @Nullable IpGeo geo, Instant firstSeen, Instant lastSeen,
                                   long loginCount) {
        return new PlayerIpsRecord(
            TextLimits.require(PLAYER_IPS.PLAYER_ID, playerId),
            TextLimits.require(PLAYER_IPS.IP, address),
            geo == null ? null : PlayerColumns.fitIfPresent(PLAYER_IPS.COUNTRY, geo.country()),
            geo == null ? null : PlayerColumns.fitIfPresent(PLAYER_IPS.REGION, geo.region()),
            geo == null ? null : PlayerColumns.fitIfPresent(PLAYER_IPS.ASN, geo.asn()),
            geo != null && geo.proxy(),
            geo != null && geo.hosting(),
            firstSeen,
            lastSeen,
            loginCount,
            null);
    }

    private static PlayerIpsRecord combineAddresses(PlayerIpsRecord first, PlayerIpsRecord second) {
        return new PlayerIpsRecord(first.getPlayerId(), first.getIp(),
            first.getCountry() != null ? first.getCountry() : second.getCountry(),
            first.getRegion() != null ? first.getRegion() : second.getRegion(),
            first.getAsn() != null ? first.getAsn() : second.getAsn(),
            first.getIsProxy() || second.getIsProxy(),
            first.getIsHosting() || second.getIsHosting(),
            earliest(first.getFirstSeenAt(), second.getFirstSeenAt()),
            latest(first.getLastSeenAt(), second.getLastSeenAt()),
            first.getLoginCount() + second.getLoginCount(),
            first.getRetainedUntilAt());
    }

    static PlayerNotesRecord note(String playerId, NoteEntry note, Instant fallback) {
        PlayerNotesRecord row = new PlayerNotesRecord();
        row.setPlayerId(TextLimits.require(PLAYER_NOTES.PLAYER_ID, playerId));
        row.setId(TextLimits.require(PLAYER_NOTES.ID, note.getId() != null ? note.getId() : EntityIds.next()));
        row.setBody(Objects.requireNonNullElse(note.getText(), ""));
        row.setKind(Objects.requireNonNull(note.getKind(), "A player note needs a kind").name());
        row.setIssuerName(TextLimits.requireIfPresent(PLAYER_NOTES.ISSUER_NAME, note.getIssuerName()));
        row.setIssuerId(TextLimits.requireIfPresent(PLAYER_NOTES.ISSUER_ID, note.getIssuerId()));
        row.setCreatedAt(note.getDate() != null ? note.getDate() : fallback);
        return row;
    }

    private static Instant earliest(Instant first, Instant second) {
        return second.isBefore(first) ? second : first;
    }

    private static Instant latest(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }
}
