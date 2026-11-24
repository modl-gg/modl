package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.LinkedAccountsResult;
import gg.modl.backend.player.dto.response.PlayerFetchResult;
import gg.modl.backend.player.dto.response.PlayerLookupResult;
import gg.modl.backend.player.dto.response.PlayerProfileResult;
import gg.modl.backend.player.dto.response.PunishmentView;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.AddressSharers;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlayerLookupService {
    public static final String ACTIVE_DATA_KEY = "active";
    public static final String EXPIRES_DATA_KEY = "expires";
    public static final String APPEALABLE_DATA_KEY = "isAppealable";

    private static final int IP_RELATED_LIMIT = 20;
    private static final int STORED_LINK_LIMIT = 1000;
    private static final int RECENT_PUNISHMENT_LIMIT = 5;
    private static final PlayerParts[] PROFILE_PARTS = PlayerParts.values();
    private static final PlayerParts[] LOOKUP_PARTS = {PlayerParts.USERNAMES, PlayerParts.PUNISHMENTS};

    private final PlayerRepository playerRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final MojangApiService mojangApiService;
    private final PlayerStatusCalculator statusCalculator;
    private final IssuerNameResolver issuerNameResolver;
    private final PlayerService playerService;
    private final PublicUrls publicUrls;
    private final Clock clock;

    public PlayerProfileResult getPlayerByUuid(Server server, String uuid, @Nullable Integer punishmentLimit, @Nullable Integer noteLimit) {
        Player player = findPlayerByUuid(uuid, PROFILE_PARTS).orElse(null);
        if (player == null) {
            return new PlayerProfileResult.NotFound("Player not found");
        }

        PunishmentTypeLookup punishmentTypes = PunishmentTypeLookup.of(server, punishmentTypeService);
        return new PlayerProfileResult.Found(toPlayerProfile(server, player, punishmentTypes, punishmentLimit, noteLimit));
    }

    public PlayerFetchResult getPlayerByMinecraftUuid(Server server, String minecraftUuid, boolean queryMojang) {
        if (minecraftUuid == null || minecraftUuid.isBlank()) {
            return new PlayerFetchResult.InvalidRequest("minecraftUuid parameter required");
        }

        Player player = findPlayerByUuid(minecraftUuid, PROFILE_PARTS).orElse(null);
        if (player == null && queryMojang) {
            Optional<MojangApiService.MojangProfile> profile = mojangApiService.lookupByUuid(minecraftUuid);
            if (profile.isPresent()) {
                return new PlayerFetchResult.Found("Player found via Mojang", toMojangProfile(profile.get()));
            }
        }

        if (player == null) {
            return new PlayerFetchResult.NotFound("Player not found");
        }

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        return new PlayerFetchResult.Found("Player found", toPlayerProfile(server, player, types));
    }

    public PlayerFetchResult getPlayerByUsername(Server server, String username, boolean queryMojang) {
        Player player = findByUsername(server, username, PROFILE_PARTS).orElse(null);
        if (player == null && queryMojang) {
            Optional<MojangApiService.MojangProfile> profile = mojangApiService.lookupByUsername(username);
            if (profile.isPresent()) {
                return new PlayerFetchResult.Found("Player found via Mojang", toMojangProfile(profile.get()));
            }
        }

        if (player == null) {
            return new PlayerFetchResult.NotFound("Player not found");
        }

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        return new PlayerFetchResult.Found("Player found", toPlayerProfile(server, player, types));
    }

    public PlayerLookupResult lookupPlayer(Server server, String query, boolean shouldQueryMojang) {
        boolean isUuid = looksLikeUuid(query);
        Player player = isUuid
                        ? findPlayerByUuid(query, LOOKUP_PARTS).orElse(null)
                        : findByUsername(server, query, LOOKUP_PARTS).orElse(null);

        if (player == null && shouldQueryMojang) {
            Optional<MojangApiService.MojangProfile> profile = isUuid
                                                               ? mojangApiService.lookupByUuid(query)
                                                               : mojangApiService.lookupByUsername(query);
            if (profile.isPresent()) {
                MojangApiService.MojangProfile mojangProfile = profile.get();
                Map<String, Object> lookupData = new LinkedHashMap<>();
                lookupData.put("minecraftUuid", mojangProfile.uuid().toString());
                lookupData.put("currentUsername", mojangProfile.name());
                lookupData.put("previousUsernames", List.of());
                lookupData.put("firstSeen", null);
                lookupData.put("lastSeen", null);
                lookupData.put("isOnline", false);
                lookupData.put("punishmentStats", Map.of(
                    "totalPunishments", 0,
                    "activePunishments", 0,
                    "bans", 0,
                    "mutes", 0,
                    "kicks", 0,
                    "warnings", 0
                ));
                lookupData.put("recentPunishments", List.of());
                return new PlayerLookupResult.Found("Player found via Mojang", lookupData);
            }
        }

        if (player == null) {
            return new PlayerLookupResult.NotFound("Player not found");
        }

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        return new PlayerLookupResult.Found("Player found", buildLookupResponse(player, types));
    }

    public PlayerProfileResult lookupProfile(Server server, String query, boolean shouldQueryMojang, @Nullable Integer punishmentLimit, @Nullable Integer noteLimit) {
        boolean isUuid = looksLikeUuid(query);
        Player player = isUuid
                        ? findPlayerByUuid(query, PROFILE_PARTS).orElse(null)
                        : findByUsername(server, query, PROFILE_PARTS).orElse(null);

        if (player == null && shouldQueryMojang) {
            Optional<MojangApiService.MojangProfile> mojangProfile = isUuid
                                                                     ? mojangApiService.lookupByUuid(query)
                                                                     : mojangApiService.lookupByUsername(query);
            if (mojangProfile.isPresent()) {
                Player mojangPlayer = findPlayerByUuid(mojangProfile.get().uuid().toString(), PROFILE_PARTS).orElse(null);
                if (mojangPlayer != null) {
                    player = mojangPlayer;
                }
            }
        }

        if (player == null) {
            return new PlayerProfileResult.NotFound("Player not found");
        }

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        return new PlayerProfileResult.Found(toPlayerProfile(server, player, types, punishmentLimit, noteLimit));
    }

    Map<String, Object> toPlayerProfile(Server server, Player player, PunishmentTypeLookup punishmentTypes) {
        return toPlayerProfile(server, player, punishmentTypes, null, null);
    }

    Map<String, Object> toPlayerProfile(Server server, Player player, PunishmentTypeLookup punishmentTypes, @Nullable Integer punishmentLimit, @Nullable Integer noteLimit) {
        return toPlayerProfile(server, player, punishmentTypes, punishmentLimit, noteLimit,
            issuerNameResolver.resolveForPunishments(server, safePunishments(player)));
    }

    Map<String, Object> toPlayerProfile(Server server, Player player, PunishmentTypeLookup punishmentTypes, @Nullable Integer punishmentLimit, @Nullable Integer noteLimit, Map<String, String> resolvedIssuers) {
        List<Map<String, Object>> usernames = safeUsernames(player).stream()
            .map(username -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("username", username.username());
                entry.put("date", username.date());
                return entry;
            })
            .toList();

        List<NoteEntry> allNotes = safeNotes(player);
        List<NoteEntry> limitedNotes = limitNewestFirst(allNotes, noteLimit,
            Comparator.comparing(NoteEntry::getDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        List<Map<String, Object>> notes = limitedNotes.stream()
            .map(note -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", note.getId());
                entry.put("text", note.getText());
                entry.put("date", note.getDate());
                entry.put("issuerName", note.getIssuerName());
                entry.put("issuerId", note.getIssuerId());
                entry.put("kind", note.getKind());
                return entry;
            })
            .toList();

        List<Map<String, Object>> ipAddresses = safeIpAddresses(player).stream()
            .map(ip -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("country", ip.getCountry());
                entry.put("region", ip.getRegion());
                entry.put("asn", ip.getAsn());
                entry.put("proxy", ip.isProxy());
                entry.put("hosting", ip.isHosting());
                entry.put("firstLogin", ip.getFirstLogin());
                entry.put("logins", ip.getFirstLogin() != null ? List.of(ip.lastSeen()) : List.of());
                entry.put("lastLogin", ip.getLastLogin());
                entry.put("loginCount", ip.getLoginCount());
                return entry;
            })
            .toList();

        List<Punishment> allPunishments = safePunishments(player);
        List<Punishment> limitedPunishments = limitNewestFirst(allPunishments, punishmentLimit,
            Comparator.comparing(Punishment::getIssued, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        List<PunishmentView> punishments = limitedPunishments.stream()
            .map(punishment -> withAccountState(punishment, punishmentTypes,
                PunishmentMapper.toPunishmentView(punishment, punishmentTypes, resolvedIssuers)))
            .toList();

        List<Map<String, Object>> pendingNotifications = player.data().pendingNotifications();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", player.getId());
        profile.put("minecraftUuid", player.getMinecraftUuid().toString());
        profile.put("usernames", usernames);
        profile.put("notes", notes);
        profile.put("ipAddresses", ipAddresses);
        profile.put("punishments", punishments);
        profile.put("pendingNotifications", pendingNotifications);
        profile.put("data", player.data().asMapAt(clock.instant()));
        if (punishmentLimit != null) {
            profile.put("punishmentCount", allPunishments.size());
        }
        if (noteLimit != null) {
            profile.put("noteCount", allNotes.size());
        }
        return profile;
    }

    private PunishmentView withAccountState(Punishment punishment, PunishmentTypeLookup types, PunishmentView view) {
        Map<String, Object> data = new LinkedHashMap<>(view.data());
        data.put(ACTIVE_DATA_KEY, statusCalculator.isPunishmentActive(punishment));
        data.put(APPEALABLE_DATA_KEY, types.isAppealable(punishment.getTypeOrdinal()));
        data.remove(EXPIRES_DATA_KEY);
        Instant expires = statusCalculator.getEffectiveExpiry(punishment);
        if (expires != null) {
            data.put(EXPIRES_DATA_KEY, expires.toEpochMilli());
        }
        return new PunishmentView(view.id(), view.issuerName(), view.issued(), view.started(), view.typeOrdinal(),
            view.type(), view.modifications(), view.notes(), view.evidence(), view.attachedTicketIds(), data,
            view.playerUuid(), view.playerName());
    }

    private Map<String, Object> buildLookupResponse(Player player, PunishmentTypeLookup types) {
        List<UsernameEntry> usernames = safeUsernames(player);
        String currentUsername = PlayerDataUtils.extractLatestUsername(usernames);
        List<String> previousUsernames = usernames.stream()
            .limit(Math.max(0, usernames.size() - 1))
            .map(UsernameEntry::username)
            .toList();

        Instant firstSeen = firstSeen(usernames, player.data().firstJoin());
        Instant lastSeen = usernames.isEmpty() ? null : usernames.getLast().date();

        int totalPunishments = player.getPunishments().size();
        int activePunishments = (int) player.getPunishments()
            .stream()
            .filter(statusCalculator::isPunishmentActive)
            .count();

        int bans = 0;
        int mutes = 0;
        int kicks = 0;
        int warnings = 0;
        for (Punishment punishment : player.getPunishments()) {
            PunishmentType type = types.live(punishment.getTypeOrdinal());
            if (type != null && type.isKick()) {
                kicks++;
                continue;
            }
            ResolvedCategory category = statusCalculator.getEffectiveCategory(punishment);
            if (category == ResolvedCategory.BAN) {
                bans++;
            } else if (category == ResolvedCategory.MUTE) {
                mutes++;
            } else {
                warnings++;
            }
        }

        List<Map<String, Object>> recentPunishments = player.getPunishments()
            .stream()
            .sorted(Comparator.comparing(Punishment::getIssued, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                .thenComparing(Punishment::getId))
            .limit(RECENT_PUNISHMENT_LIMIT)
            .map(punishment -> {
                String typeName = types.name(punishment.getTypeOrdinal());

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("id", punishment.getId());
                response.put("type", typeName);
                response.put("issuer", punishment.getIssuerName());
                response.put("issuedAt", punishment.getIssued());
                response.put("expiresAt", statusCalculator.getEffectiveExpiry(punishment));
                response.put("isActive", statusCalculator.isPunishmentActive(punishment));
                return response;
            })
            .toList();


        Map<String, Object> data = new LinkedHashMap<>();
        data.put("minecraftUuid", player.getMinecraftUuid().toString());
        data.put("currentUsername", currentUsername);
        data.put("previousUsernames", previousUsernames);
        data.put("firstSeen", firstSeen);
        data.put("lastSeen", lastSeen);
        data.put("isOnline", player.data().isOnline());
        data.put("punishmentStats", Map.of(
            "totalPunishments", totalPunishments,
            "activePunishments", activePunishments,
            "bans", bans,
            "mutes", mutes,
            "kicks", kicks,
            "warnings", warnings
        ));
        data.put("recentPunishments", recentPunishments);
        data.put("profileUrl", publicUrls.playerProfile(player.getMinecraftUuid().toString()));
        return data;
    }

    private Map<String, Object> toMojangProfile(MojangApiService.MojangProfile profile) {
        return Map.of(
            "minecraftUuid", profile.uuid().toString(),
            "usernames", List.of(Map.of("username", profile.name())),
            "notes", List.of(),
            "ipAddresses", List.of(),
            "punishments", List.of(),
            "pendingNotifications", List.of(),
            "data", Map.of()
        );
    }

    @Nullable
    private static Instant firstSeen(List<UsernameEntry> usernames, @Nullable Instant firstJoin) {
        Instant firstNamed = usernames.isEmpty() ? null : usernames.getFirst().date();
        if (firstNamed == null) {
            return firstJoin;
        }
        return firstJoin != null && firstJoin.isBefore(firstNamed) ? firstJoin : firstNamed;
    }

    private Optional<Player> findPlayerByUuid(String uuid, PlayerParts... parts) {
        return playerRepository.findByMinecraftUuid(UuidUtils.normalize(uuid), parts);
    }

    private Optional<Player> findByUsername(Server server, String username, PlayerParts... parts) {
        return playerService.findBestByUsername(server, username, parts);
    }

    private boolean looksLikeUuid(String query) {
        return query.contains("-") && query.length() == 36;
    }

    private List<UsernameEntry> safeUsernames(Player player) {
        return player.getUsernames() != null ? player.getUsernames() : List.of();
    }

    private List<NoteEntry> safeNotes(Player player) {
        return player.getNotes() != null ? player.getNotes() : List.of();
    }

    private List<Punishment> safePunishments(Player player) {
        return player.getPunishments() != null ? player.getPunishments() : List.of();
    }

    private List<IPEntry> safeIpAddresses(Player player) {
        return player.getIpAddresses() != null ? player.getIpAddresses() : List.of();
    }

    private <T> List<T> limitNewestFirst(List<T> values, @Nullable Integer limit, Comparator<? super T> newestFirst) {
        if (limit == null) {
            return values;
        }
        return values.stream()
            .sorted(newestFirst)
            .limit(Math.max(0, limit))
            .toList();
    }


    public LinkedAccountsResult getLinkedAccounts(Server server, String uuid, @Nullable Integer page, @Nullable Integer limit) {
        Player player = findPlayerByUuid(uuid, PlayerParts.IPS, PlayerParts.LINKS).orElse(null);
        if (player == null) {
            return new LinkedAccountsResult.NotFound("Player not found");
        }

        List<String> ips = safeIpAddresses(player).stream().map(IPEntry::getIpAddress).toList();
        AddressSharers sharers = playerRepository.findRecentByIps(ips, player.getId(), IP_RELATED_LIMIT);
        List<String> relatedIds = relatedPlayerIds(player, sharers);
        if (page == null || limit == null) {
            return new LinkedAccountsResult.Found(toProfiles(server, relatedIds), null, null, null, sharers.truncated());
        }

        PaginationHelper.PageResult<String> result = PaginationHelper.paginate(relatedIds, page, limit);
        return new LinkedAccountsResult.Found(
            toProfiles(server, result.items()), result.totalCount(), result.page(), result.hasMore(), sharers.truncated());
    }

    private List<String> relatedPlayerIds(Player player, AddressSharers sharers) {
        Map<String, String> idsByUuid = new LinkedHashMap<>();
        sharers.players().forEach(related -> idsByUuid.put(related.getMinecraftUuid().toString(), related.getId()));

        List<String> storedOnly = player.data().linkedAccountUuids().stream()
            .filter(linkedUuid -> !idsByUuid.containsKey(linkedUuid))
            .limit(STORED_LINK_LIMIT)
            .toList();
        playerRepository.findByMinecraftUuids(storedOnly)
            .forEach(linked -> idsByUuid.put(linked.getMinecraftUuid().toString(), linked.getId()));
        return List.copyOf(idsByUuid.values());
    }

    private List<Map<String, Object>> toProfiles(Server server, List<String> playerIds) {
        if (playerIds.isEmpty()) {
            return List.of();
        }
        Map<String, Player> playersById = playerRepository.findByIds(playerIds, PROFILE_PARTS).stream()
            .collect(Collectors.toMap(Player::getId, Function.identity()));
        List<Player> players = playerIds.stream().filter(playersById::containsKey).map(playersById::get).toList();
        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPlayers(server, players);
        return players.stream()
            .map(linked -> toPlayerProfile(server, linked, types, null, null, resolvedIssuers))
            .toList();
    }
}
