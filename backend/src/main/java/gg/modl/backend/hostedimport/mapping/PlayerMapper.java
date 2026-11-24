package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.FoldedDates;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.migration.service.ImportUnits;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.LegacyNoteText;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerDataKeys;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PlayerNotification;
import gg.modl.backend.player.repository.PlayerSnapshot;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class PlayerMapper {
    private static final int PLAYER_ID_LIMIT = 36;
    private static final int NOTE_ID_LIMIT = 100;
    private static final int NOTIFICATION_ID_LIMIT = 64;
    private static final Set<String> DATA_KEYS = Set.of(
        PlayerDataKeys.IS_ONLINE, PlayerDataKeys.LAST_LOGIN, PlayerDataKeys.LAST_LOGOUT, PlayerDataKeys.LAST_SERVER,
        PlayerDataKeys.TOTAL_PLAYTIME_SECONDS, PlayerDataKeys.FIRST_JOIN, PlayerDataKeys.LAST_SKIN_HASH,
        PlayerDataKeys.LAST_LINKED_UPDATE, PlayerDataKeys.PENDING_NOTIFICATIONS, PlayerDataKeys.LINKED_ACCOUNTS);
    private static final Set<String> NOTIFICATION_IDENTITY_KEYS = Set.of("id", "_id", "targetPlayerUuid");

    private final ImportContext context;
    private final PunishmentMapper punishments;
    private final ImportFit fit;

    public PlayerMapper(ImportContext context) {
        this.context = context;
        this.punishments = new PunishmentMapper(context);
        this.fit = context.fit();
    }

    public Optional<MappedPlayer> map(ArchiveDocument source) {
        UUID uuid = source.uuid("minecraftUuid");
        if (uuid == null) {
            context.issues().report(IssueCode.PLAYER_INVALID_UUID, Objects.requireNonNullElse(source.id(), ""));
            context.issues().skipped(ImportSubject.PLAYERS);
            return Optional.empty();
        }
        String playerUuid = uuid.toString();
        String sourceId = source.id();
        String id = context.ids().adopt(IdKind.PLAYER, "", sourceId, PLAYER_ID_LIMIT);
        ArchiveDocument data = source.document("data").orElseGet(() -> ArchiveDocument.of(Map.of()));
        List<NoteEntry> notes = notes(source.documents("notes"), id);
        List<Punishment> mappedPunishments = punishments.punishments(source.documents("punishments"), playerUuid);
        Player player = Player.builder()
            .id(id)
            .minecraftUuid(uuid)
            .usernames(usernames(source.documents("usernames")))
            .notes(notes)
            .ipAddresses(addresses(source.documents("ipAddresses")))
            .punishments(mappedPunishments)
            .build();
        player.replaceData(playerData(data));
        context.issues().imported(ImportSubject.PLAYERS);
        return Optional.of(new MappedPlayer(
            new PlayerSnapshot(player, createdAt(data, notes, mappedPunishments)),
            linkedUuids(data, playerUuid),
            notifications(data, id)));
    }

    private Instant createdAt(ArchiveDocument data, List<NoteEntry> notes, List<Punishment> mappedPunishments) {
        Instant firstJoin = data.instant(PlayerDataKeys.FIRST_JOIN);
        if (firstJoin != null) {
            return firstJoin;
        }
        return Stream.concat(notes.stream().map(NoteEntry::getDate), mappedPunishments.stream().map(Punishment::getIssued))
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(context.exportTime());
    }

    private List<UsernameEntry> usernames(List<ArchiveDocument> sources) {
        List<UsernameEntry> usernames = new ArrayList<>();
        for (ArchiveDocument source : sources) {
            String username = fit.columnIfPresent(PLAYER_USERNAMES.USERNAME, source.text("username"));
            if (username != null && !username.isBlank()) {
                usernames.add(new UsernameEntry(username, source.instant("date")));
            }
        }
        return usernames;
    }

    private List<NoteEntry> notes(List<ArchiveDocument> sources, String playerId) {
        List<NoteEntry> notes = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            ArchiveDocument source = sources.get(index);
            String sourceId = source.id();
            String id = sourceId != null && used.add(sourceId)
                ? context.ids().adopt(IdKind.PLAYER_NOTE, playerId, sourceId, NOTE_ID_LIMIT)
                : context.ids().derive(IdKind.PLAYER_NOTE, playerId + "#" + index, Objects.requireNonNullElse(sourceId, ""));
            used.add(id);
            String issuerSourceId = source.idOf("issuerId");
            LegacyNoteText legacy = LegacyNoteText.of(Objects.requireNonNullElse(source.string("text"), ""));
            notes.add(new NoteEntry(
                id,
                fit.text(context.urls().rewriteText(legacy.text())),
                Objects.requireNonNullElse(source.instant("date"), context.exportTime()),
                fit.columnIfPresent(PLAYER_NOTES.ISSUER_NAME, context.issuers().name(issuerSourceId, source.string("issuerName"))),
                fit.columnIfPresent(PLAYER_NOTES.ISSUER_ID, context.issuers().staffId(issuerSourceId)),
                legacy.kind()));
        }
        return notes;
    }

    private List<IPEntry> addresses(List<ArchiveDocument> sources) {
        List<IPEntry> addresses = new ArrayList<>();
        for (ArchiveDocument source : sources) {
            String ip = IpAddresses.literalOrNull(source.text("ipAddress"));
            if (ip == null) {
                context.issues().report(IssueCode.IP_INVALID, 1);
                continue;
            }
            FoldedDates logins = source.raw("logins") instanceof FoldedDates folded ? folded : null;
            Instant firstLogin = earliest(source.instant("firstLogin"), logins == null ? null : logins.earliest());
            Instant lastLogin = latest(firstLogin, logins == null ? null : logins.latest());
            Instant firstSeen = Objects.requireNonNullElse(firstLogin, context.exportTime());
            Instant lastSeen = Objects.requireNonNullElse(lastLogin, firstSeen);
            if (!context.keepsIpHistory() && lastSeen.isBefore(context.ipCutoff())) {
                context.issues().report(IssueCode.IP_NOT_KEPT, 1);
                continue;
            }
            addresses.add(IPEntry.builder()
                .ipAddress(ip)
                .country(fit.columnIfPresent(PLAYER_IPS.COUNTRY, source.text("country")))
                .region(fit.columnIfPresent(PLAYER_IPS.REGION, source.text("region")))
                .asn(fit.columnIfPresent(PLAYER_IPS.ASN, source.text("asn")))
                .proxy(source.bool("proxy"))
                .hosting(source.bool("hosting"))
                .firstLogin(firstSeen)
                .lastLogin(lastSeen)
                .loginCount(Math.max(1L, logins == null ? 1L : logins.count()))
                .build());
        }
        return addresses;
    }

    private Map<String, Object> playerData(ArchiveDocument source) {
        Map<String, Object> data = new LinkedHashMap<>();
        source.fields().forEach((key, value) -> {
            if (!DATA_KEYS.contains(key)) {
                data.put(key, ImportedUrls.plain(value));
            }
        });
        data.put(PlayerDataKeys.IS_ONLINE, false);
        putInstant(data, PlayerDataKeys.FIRST_JOIN, source.instant(PlayerDataKeys.FIRST_JOIN));
        putInstant(data, PlayerDataKeys.LAST_LOGIN, source.instant(PlayerDataKeys.LAST_LOGIN));
        putInstant(data, PlayerDataKeys.LAST_LOGOUT, source.instant(PlayerDataKeys.LAST_LOGOUT));
        putInstant(data, PlayerDataKeys.LAST_LINKED_UPDATE, source.instant(PlayerDataKeys.LAST_LINKED_UPDATE));
        String lastServer = fit.columnIfPresent(PLAYERS.LAST_SERVER_NAME, source.text(PlayerDataKeys.LAST_SERVER));
        if (lastServer != null) {
            data.put(PlayerDataKeys.LAST_SERVER, lastServer);
        }
        Long playtime = source.longValue(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS);
        if (playtime != null) {
            data.put(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS, Math.max(0L, playtime));
        }
        String skin = source.text(PlayerDataKeys.LAST_SKIN_HASH);
        if (skin != null) {
            data.put(PlayerDataKeys.LAST_SKIN_HASH, skin);
        }
        return data;
    }

    private List<String> linkedUuids(ArchiveDocument data, String playerUuid) {
        List<String> linked = new ArrayList<>();
        for (@Nullable Object value : data.list(PlayerDataKeys.LINKED_ACCOUNTS)) {
            UUID uuid = ArchiveDocument.uuidOf(value);
            if (uuid == null) {
                context.issues().report(IssueCode.REFERENCE_DANGLING, playerUuid);
            } else if (!uuid.toString().equals(playerUuid) && !linked.contains(uuid.toString())) {
                linked.add(uuid.toString());
            }
        }
        return linked;
    }

    private List<PlayerNotification> notifications(ArchiveDocument data, String playerId) {
        List<PlayerNotification> notifications = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<ArchiveDocument> sources = data.documents(PlayerDataKeys.PENDING_NOTIFICATIONS);
        for (int index = 0; index < sources.size(); index++) {
            ArchiveDocument source = sources.get(index);
            Instant createdAt = source.raw("timestamp") instanceof Number || source.raw("timestamp") instanceof Instant
                ? ArchiveDocument.instantOf(source.raw("timestamp"))
                : null;
            Instant created = Objects.requireNonNullElse(createdAt, context.exportTime());
            Instant expiresAt = created.plus(context.notificationLifetime());
            String message = source.string("message");
            if (message == null || message.isBlank() || !expiresAt.isAfter(context.acceptedAt())) {
                context.issues().report(IssueCode.NOTIFICATION_EXPIRED, 1);
                continue;
            }
            String sourceId = source.id();
            String id = sourceId != null && used.add(sourceId)
                ? context.ids().adopt(IdKind.NOTIFICATION, playerId, sourceId, NOTIFICATION_ID_LIMIT)
                : context.ids().derive(IdKind.NOTIFICATION, playerId + "#" + index, Objects.requireNonNullElse(sourceId, ""));
            used.add(id);
            Map<String, Object> payload = new LinkedHashMap<>();
            source.fields().forEach((key, value) -> {
                if (!NOTIFICATION_IDENTITY_KEYS.contains(key)) {
                    payload.put(key, context.urls().rewriteTree(value));
                }
            });
            notifications.add(new PlayerNotification(id, payload, created, expiresAt));
            context.issues().imported(ImportSubject.NOTIFICATIONS);
        }
        return notifications;
    }

    private static void putInstant(Map<String, Object> data, String key, @Nullable Instant value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    @Nullable
    private static Instant earliest(@Nullable Instant first, @Nullable Instant second) {
        if (first == null) {
            return second;
        }
        return second != null && second.isBefore(first) ? second : first;
    }

    @Nullable
    private static Instant latest(@Nullable Instant first, @Nullable Instant second) {
        if (first == null) {
            return second;
        }
        return second != null && second.isAfter(first) ? second : first;
    }

    public record MappedPlayer(PlayerSnapshot snapshot, List<String> linkedUuids, List<PlayerNotification> notifications) {
        public int rows() {
            return ImportUnits.rowCount(snapshot.player()) + notifications.size() + linkedUuids.size();
        }
    }
}
