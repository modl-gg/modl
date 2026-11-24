package gg.modl.backend.player;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.dto.response.PlayerDetailResponse;
import gg.modl.backend.player.dto.response.PlayerSearchResult;
import gg.modl.backend.player.dto.response.PunishmentResponse;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PlayerDataUtils;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.PanelResource;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlayerService {
    private final PlayerRepository playerRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final RealtimeEventPublisher realtimePublisher;
    private final PunishmentQueryService punishmentQueryService;
    private final Clock clock;
    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final int SEARCH_CANDIDATE_LIMIT = 100;
    private static final int RANK_EXACT_CURRENT_USERNAME = 0;
    private static final int RANK_EXACT_PAST_USERNAME = 1;
    private static final int RANK_PREFIX_CURRENT_USERNAME = 2;
    private static final int RANK_PREFIX_PAST_USERNAME = 3;
    private static final int RANK_CONTAINS_CURRENT_USERNAME = 4;
    private static final int RANK_CONTAINS_PAST_USERNAME = 5;
    private static final int RANK_NO_MATCH = Integer.MAX_VALUE;
    private static final int DETAIL_PUNISHMENT_CAP = 100;
    private static final int DETAIL_NOTE_CAP = 100;

    public List<PlayerSearchResult> searchPlayers(Server server, String searchTerm) {
        String normalizedSearch = searchTerm == null ? "" : searchTerm.trim();
        if (normalizedSearch.isEmpty()) {
            return List.of();
        }

        List<Player> players;
        if (isUuid(normalizedSearch)) {
            players = playerRepository.findByMinecraftUuid(UuidUtils.normalize(normalizedSearch), PlayerParts.USERNAMES)
                .map(List::of)
                .orElseGet(List::of);
        } else {
            String normalizedSearchLower = normalizedSearch.toLowerCase(Locale.ROOT);
            players = playerRepository.search(normalizedSearch, SEARCH_CANDIDATE_LIMIT).stream()
                .sorted(Comparator
                    .comparingInt((Player player) -> computeMatchRank(player, normalizedSearchLower))
                    .thenComparing((Player a, Player b) -> Long.compare(getLastLoginMillis(b), getLastLoginMillis(a)))
                    .thenComparing(player -> player.getMinecraftUuid().toString()))
                .limit(SEARCH_RESULT_LIMIT)
                .toList();
        }
        if (players.isEmpty()) {
            return List.of();
        }

        Set<String> banHolders = playerRepository.findActiveBanHolders(
            players.stream().map(Player::getId).toList(), clock.instant());
        return players.stream()
            .map(player -> toPlayerSearchResult(player, banHolders.contains(player.getId())))
            .toList();
    }

    public Optional<Player> findBestByUsername(Server server, String username, PlayerParts... parts) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (normalizedUsername.isEmpty()) {
            return Optional.empty();
        }
        return playerRepository.findByUsernameIgnoreCase(normalizedUsername, parts);
    }

    private PlayerSearchResult toPlayerSearchResult(Player player, boolean banned) {
        boolean online = player.data().isOnline();
        return new PlayerSearchResult(
            player.getMinecraftUuid().toString(),
            PlayerDataUtils.extractLatestUsername(player.getUsernames()),
            searchStatus(banned, online),
            player.data().lastLogin(),
            online);
    }

    private String searchStatus(boolean banned, boolean online) {
        if (banned) {
            return "Banned";
        }
        return online ? "Online" : "Offline";
    }

    private int computeMatchRank(Player player, String normalizedSearchLower) {
        List<UsernameEntry> usernames = player.getUsernames();
        if (usernames == null || usernames.isEmpty()) {
            return RANK_NO_MATCH;
        }

        String currentUsername = usernames.get(usernames.size() - 1).username();
        if (equalsIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_EXACT_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (equalsIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_EXACT_PAST_USERNAME;
            }
        }

        if (startsWithIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_PREFIX_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (startsWithIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_PREFIX_PAST_USERNAME;
            }
        }

        if (containsIgnoreCase(currentUsername, normalizedSearchLower)) {
            return RANK_CONTAINS_CURRENT_USERNAME;
        }

        for (int i = 0; i < usernames.size() - 1; i++) {
            if (containsIgnoreCase(usernames.get(i).username(), normalizedSearchLower)) {
                return RANK_CONTAINS_PAST_USERNAME;
            }
        }

        return RANK_NO_MATCH;
    }

    private boolean equalsIgnoreCase(String value, String normalizedSearchLower) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(normalizedSearchLower);
    }

    private boolean startsWithIgnoreCase(String value, String normalizedSearchLower) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(normalizedSearchLower);
    }

    private boolean containsIgnoreCase(String value, String normalizedSearchLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedSearchLower);
    }

    private long getLastLoginMillis(Player player) {
        Instant lastLogin = player.data().lastLogin();
        return lastLogin != null ? lastLogin.toEpochMilli() : Long.MIN_VALUE;
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public PlayerDetailResponse getPlayerDetails(Server server, UUID minecraftUuid) {
        Player player = playerRepository.findByMinecraftUuid(minecraftUuid.toString(), PlayerParts.values())
            .orElseThrow(PlayerService::playerNotFound);
        return buildPlayerDetailResponse(server, player);
    }

    private PlayerDetailResponse buildPlayerDetailResponse(Server server, Player player) {
        PlayerStatusCalculator.PlayerStatus status = statusCalculator.calculateStatus(server, player.getPunishments());

        List<PunishmentResponse> punishmentResponses = capPunishments(
            punishmentQueryService.getPlayerPunishmentResponses(server, player));

        List<IPEntry> sanitizedIps = player.getIpAddresses().stream()
            .map(PlayerService::withoutAddress)
            .toList();
        IPEntry latestIp = sanitizedIps.isEmpty() ? null : sanitizedIps.getLast();

        Instant now = clock.instant();
        double playtimeHours = player.data().playtimeSecondsAt(now) / 3600.0;

        return new PlayerDetailResponse(
            player.getId(),
            player.getMinecraftUuid().toString(),
            player.getUsernames(),
            capNotes(player.getNotes()),
            sanitizedIps,
            punishmentResponses,
            player.data().asMapAt(now),
            status.social(),
            status.gameplay(),
            status.socialPoints(),
            status.gameplayPoints(),
            latestIp,
            player.data().lastServer(),
            playtimeHours
        );
    }

    private static IPEntry withoutAddress(IPEntry ip) {
        return IPEntry.builder()
            .ipAddress(null)
            .country(ip.getCountry())
            .region(ip.getRegion())
            .asn(ip.getAsn())
            .proxy(ip.isProxy())
            .vpnStatus(ip.getVpnStatus())
            .hosting(ip.isHosting())
            .firstLogin(ip.getFirstLogin())
            .lastLogin(ip.getLastLogin())
            .loginCount(ip.getLoginCount())
            .build();
    }

    private List<PunishmentResponse> capPunishments(List<PunishmentResponse> punishments) {
        if (punishments.size() <= DETAIL_PUNISHMENT_CAP) {
            return punishments;
        }
        List<PunishmentResponse> active = new ArrayList<>();
        List<PunishmentResponse> inactive = new ArrayList<>();
        for (PunishmentResponse punishment : punishments) {
            (punishment.active() ? active : inactive).add(punishment);
        }
        inactive.sort(Comparator.comparing(PunishmentResponse::issued, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        List<PunishmentResponse> capped = new ArrayList<>(active);
        for (PunishmentResponse punishment : inactive) {
            if (capped.size() >= DETAIL_PUNISHMENT_CAP) {
                break;
            }
            capped.add(punishment);
        }
        return capped;
    }

    private List<NoteEntry> capNotes(List<NoteEntry> notes) {
        if (notes == null || notes.size() <= DETAIL_NOTE_CAP) {
            return notes;
        }
        return notes.stream()
            .sorted(Comparator.comparing(NoteEntry::getDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .limit(DETAIL_NOTE_CAP)
            .toList();
    }

    public Player createPlayer(Server server, UUID minecraftUuid, String username) {
        Player player = Player.builder()
            .id(EntityIds.next())
            .minecraftUuid(minecraftUuid)
            .usernames(new ArrayList<>(List.of(new UsernameEntry(username, clock.instant()))))
            .build();
        if (!playerRepository.insertIfAbsent(player)) {
            throw new ConflictException("A player with this Minecraft UUID already exists");
        }
        invalidatePlayer(server, minecraftUuid);
        return player;
    }

    public Player addUsername(Server server, UUID minecraftUuid, String username) {
        Player player = requirePlayer(minecraftUuid);
        playerRepository.recordUsername(player.getId(), username, clock.instant());
        invalidatePlayer(server, minecraftUuid);
        return requirePlayer(minecraftUuid, PlayerParts.USERNAMES);
    }

    public Player addNote(Server server, UUID minecraftUuid, String text, String issuerName, @Nullable String issuerId) {
        Player player = requirePlayer(minecraftUuid);
        playerRepository.addNote(player.getId(), NoteEntry.builder()
            .id(EntityIds.next())
            .text(text)
            .kind(PlayerNoteKind.NOTE)
            .date(clock.instant())
            .issuerName(issuerName)
            .issuerId(issuerId)
            .build());
        invalidatePlayer(server, minecraftUuid);
        return requirePlayer(minecraftUuid, PlayerParts.NOTES);
    }

    public Player addIp(Server server, UUID minecraftUuid, String ipAddress) {
        if (IpAddresses.literalOrNull(ipAddress) == null) {
            throw new ValidationException("A valid IP address is required");
        }
        Player player = requirePlayer(minecraftUuid);
        playerRepository.recordIp(player.getId(), ipAddress, clock.instant());
        invalidatePlayer(server, minecraftUuid);
        return requirePlayer(minecraftUuid, PlayerParts.IPS);
    }

    private Player requirePlayer(UUID minecraftUuid, PlayerParts... parts) {
        return playerRepository.findByMinecraftUuid(minecraftUuid.toString(), parts)
            .orElseThrow(PlayerService::playerNotFound);
    }

    private static ResourceNotFoundException playerNotFound() {
        return new ResourceNotFoundException("Player not found");
    }

    private void invalidatePlayer(Server server, UUID minecraftUuid) {
        realtimePublisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_PLAYERS, minecraftUuid.toString());
    }
}
