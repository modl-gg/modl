package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerNotesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerNotificationsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerUsernamesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.repository.AddressVpnStatus;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerDataKeys;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jspecify.annotations.Nullable;

final class PlayerReader {
    private final DSLContext dsl;
    private final JsonColumns json;
    private final PunishmentChildren punishmentChildren;

    PlayerReader(DSLContext dsl, JsonColumns json) {
        this.dsl = dsl;
        this.json = json;
        this.punishmentChildren = new PunishmentChildren(dsl, new PunishmentRows(json));
    }

    List<Player> assemble(List<PlayersRecord> headers, Instant now, PlayerParts... requested) {
        if (headers.isEmpty()) {
            return List.of();
        }
        Set<PlayerParts> parts = EnumSet.noneOf(PlayerParts.class);
        parts.addAll(List.of(requested));
        List<String> ids = headers.stream().map(PlayersRecord::getId).toList();
        Children children = new Children(
            parts.contains(PlayerParts.USERNAMES) ? usernames(ids) : null,
            parts.contains(PlayerParts.NOTES) ? notes(ids) : Map.of(),
            parts.contains(PlayerParts.IPS) ? addresses(ids) : Map.of(),
            parts.contains(PlayerParts.LINKS) ? linkedUuids(ids) : Map.of(),
            parts.contains(PlayerParts.NOTIFICATIONS) ? notifications(ids, now) : Map.of(),
            parts.contains(PlayerParts.PUNISHMENTS) ? punishments(ids) : Map.of());
        return headers.stream().map(header -> toPlayer(header, children)).toList();
    }

    Map<String, List<PlayerNotification>> notifications(Collection<String> playerIds, Instant now) {
        return byPlayer(playerIds, chunk -> dsl.selectFrom(PLAYER_NOTIFICATIONS)
                .where(PLAYER_NOTIFICATIONS.PLAYER_ID.in(chunk))
                .and(PLAYER_NOTIFICATIONS.EXPIRES_AT.gt(now))
                .orderBy(PLAYER_NOTIFICATIONS.PLAYER_ID, PLAYER_NOTIFICATIONS.CREATED_AT, PLAYER_NOTIFICATIONS.ID)
                .fetch(),
            PlayerNotificationsRecord::getPlayerId, this::toNotification);
    }

    private Map<String, List<PlayerUsernamesRecord>> usernames(List<String> playerIds) {
        return byPlayer(playerIds, chunk -> dsl.selectFrom(PLAYER_USERNAMES)
                .where(PLAYER_USERNAMES.PLAYER_ID.in(chunk))
                .orderBy(PLAYER_USERNAMES.PLAYER_ID, PLAYER_USERNAMES.LAST_SEEN_AT, PLAYER_USERNAMES.USERNAME_LOWER)
                .fetch(),
            PlayerUsernamesRecord::getPlayerId, Function.identity());
    }

    private Map<String, List<NoteEntry>> notes(List<String> playerIds) {
        return byPlayer(playerIds, chunk -> dsl.selectFrom(PLAYER_NOTES)
                .where(PLAYER_NOTES.PLAYER_ID.in(chunk))
                .orderBy(PLAYER_NOTES.PLAYER_ID, PLAYER_NOTES.SEQ)
                .fetch(),
            PlayerNotesRecord::getPlayerId, PlayerReader::toNote);
    }

    private Map<String, List<IPEntry>> addresses(List<String> playerIds) {
        Field<String> vpnStatus = AddressVpnStatus.of(PLAYER_IPS.IS_PROXY).as("vpn_status");
        return byPlayer(playerIds, chunk -> dsl.select(PLAYER_IPS.fields()).select(vpnStatus)
                .from(PLAYER_IPS)
                .leftJoin(IP_LOOKUPS).on(AddressVpnStatus.lookupOf(PLAYER_IPS.IP))
                .where(PLAYER_IPS.PLAYER_ID.in(chunk))
                .orderBy(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.LAST_SEEN_AT, PLAYER_IPS.FIRST_SEEN_AT, PLAYER_IPS.IP)
                .fetch(),
            row -> row.get(PLAYER_IPS.PLAYER_ID),
            row -> toAddress(row.into(PLAYER_IPS), VpnStatus.fromStored(row.get(vpnStatus))));
    }

    private Map<String, List<String>> linkedUuids(List<String> playerIds) {
        return byPlayer(playerIds, chunk -> dsl.select(PLAYER_LINKS.PLAYER_ID, PLAYERS.MINECRAFT_UUID)
                .from(PLAYER_LINKS)
                .join(PLAYERS).on(PLAYERS.ID.eq(PLAYER_LINKS.LINKED_PLAYER_ID))
                .where(PLAYER_LINKS.PLAYER_ID.in(chunk))
                .orderBy(PLAYER_LINKS.PLAYER_ID, PLAYER_LINKS.LINKED_AT, PLAYER_LINKS.LINKED_PLAYER_ID)
                .fetch(),
            Record2::value1, Record2::value2);
    }

    private Map<String, List<Punishment>> punishments(List<String> playerIds) {
        List<PunishmentsRecord> headers = InClauseChunks.collect(playerIds, chunk -> dsl.selectFrom(PUNISHMENTS)
            .where(PUNISHMENTS.PLAYER_ID.in(chunk))
            .orderBy(PUNISHMENTS.PLAYER_ID, PUNISHMENTS.ISSUED_AT, PUNISHMENTS.ID)
            .fetch());
        List<Punishment> punishments = punishmentChildren.complete(headers);
        Map<String, List<Punishment>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            grouped.computeIfAbsent(headers.get(index).getPlayerId(), ignored -> new ArrayList<>()).add(punishments.get(index));
        }
        return grouped;
    }

    private static <R, V> Map<String, List<V>> byPlayer(Collection<String> playerIds,
                                                        Function<List<String>, ? extends Collection<R>> query,
                                                        Function<? super R, String> playerId,
                                                        Function<? super R, ? extends V> value) {
        Map<String, List<V>> grouped = new LinkedHashMap<>();
        for (R row : InClauseChunks.collect(playerIds, query)) {
            grouped.computeIfAbsent(playerId.apply(row), ignored -> new ArrayList<>()).add(value.apply(row));
        }
        return grouped;
    }

    private Player toPlayer(PlayersRecord header, Children children) {
        String id = header.getId();
        Map<String, List<PlayerUsernamesRecord>> loadedUsernames = children.usernames();
        List<UsernameEntry> usernames = loadedUsernames == null
            ? new ArrayList<>(List.of(new UsernameEntry(header.getUsername(), null)))
            : history(header.getUsernameLower(), loadedUsernames.getOrDefault(id, List.of()));
        return Player.builder()
            .id(id)
            .minecraftUuid(UUID.fromString(header.getMinecraftUuid()))
            .usernames(usernames)
            .notes(new ArrayList<>(children.notes().getOrDefault(id, List.of())))
            .ipAddresses(new ArrayList<>(children.addresses().getOrDefault(id, List.of())))
            .punishments(new ArrayList<>(children.punishments().getOrDefault(id, List.of())))
            .data(data(header, children))
            .build();
    }

    private static List<UsernameEntry> history(String currentKey, List<PlayerUsernamesRecord> rows) {
        return rows.stream()
            .sorted(Comparator.comparing(row -> row.getUsernameLower().equals(currentKey)))
            .map(row -> new UsernameEntry(row.getUsername(), row.getLastSeenAt()))
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private Map<String, Object> data(PlayersRecord header, Children children) {
        Map<String, Object> data = new HashMap<>(json.readMap(header.getExtraJson()));
        data.put(PlayerDataKeys.IS_ONLINE, header.getIsOnline());
        putIfPresent(data, PlayerDataKeys.FIRST_JOIN, header.getFirstJoinAt());
        putIfPresent(data, PlayerDataKeys.LAST_LOGIN, header.getLastLoginAt());
        putIfPresent(data, PlayerDataKeys.LAST_LOGOUT, header.getLastLogoutAt());
        putIfPresent(data, PlayerDataKeys.LAST_SERVER, header.getLastServerName());
        putIfPresent(data, PlayerDataKeys.LAST_SKIN_HASH, header.getLastSkinHash());
        putIfPresent(data, PlayerDataKeys.LAST_LINKED_UPDATE, header.getLastLinkedUpdateAt());
        if (header.getTotalPlaytimeSeconds() > 0) {
            data.put(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS, header.getTotalPlaytimeSeconds());
        }
        List<String> linked = children.linkedUuids().get(header.getId());
        if (linked != null) {
            data.put(PlayerDataKeys.LINKED_ACCOUNTS, List.copyOf(linked));
        }
        List<PlayerNotification> notifications = children.notifications().get(header.getId());
        if (notifications != null) {
            data.put(PlayerDataKeys.PENDING_NOTIFICATIONS,
                notifications.stream().map(PlayerNotification::payload).toList());
        }
        return data;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private PlayerNotification toNotification(PlayerNotificationsRecord row) {
        Map<String, Object> payload = new LinkedHashMap<>(json.readMap(row.getPayloadJson()));
        payload.put("id", row.getId());
        return new PlayerNotification(row.getId(), payload, row.getCreatedAt(), row.getExpiresAt());
    }

    private static NoteEntry toNote(PlayerNotesRecord row) {
        return new NoteEntry(row.getId(), row.getBody(), row.getCreatedAt(), row.getIssuerName(), row.getIssuerId(),
            PlayerNoteKind.valueOf(row.getKind()));
    }

    private static IPEntry toAddress(PlayerIpsRecord row, VpnStatus vpnStatus) {
        return IPEntry.builder()
            .ipAddress(row.getIp())
            .country(row.getCountry())
            .region(row.getRegion())
            .asn(row.getAsn())
            .proxy(row.getIsProxy())
            .vpnStatus(vpnStatus)
            .hosting(row.getIsHosting())
            .firstLogin(row.getFirstSeenAt())
            .lastLogin(row.getLastSeenAt())
            .loginCount(row.getLoginCount())
            .build();
    }

    private record Children(
        @Nullable Map<String, List<PlayerUsernamesRecord>> usernames,
        Map<String, List<NoteEntry>> notes,
        Map<String, List<IPEntry>> addresses,
        Map<String, List<String>> linkedUuids,
        Map<String, List<PlayerNotification>> notifications,
        Map<String, List<Punishment>> punishments
    ) {
    }
}
