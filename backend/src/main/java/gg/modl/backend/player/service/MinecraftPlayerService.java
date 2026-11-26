package gg.modl.backend.player.service;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.request.AcknowledgeNotificationsRequest;
import gg.modl.backend.player.dto.request.OnlinePlayerPage;
import gg.modl.backend.player.dto.response.AcknowledgeResult;
import gg.modl.backend.player.dto.response.CreateNoteResult;
import gg.modl.backend.player.dto.response.OnlinePlayersResult;
import gg.modl.backend.player.dto.response.PaginatedNotesResult;
import gg.modl.backend.player.dto.response.PaginatedPunishmentsResult;
import gg.modl.backend.player.dto.response.PardonResult;
import gg.modl.backend.player.dto.response.PlayerLoginResult;
import gg.modl.backend.player.dto.response.PlayerReportsResult;
import gg.modl.backend.player.dto.response.PunishmentView;
import gg.modl.backend.player.dto.response.SimpleActionResult;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.repository.LoginRecord;
import gg.modl.backend.player.repository.LoginUpdate;
import gg.modl.backend.player.repository.PlayerColumns;
import gg.modl.backend.player.repository.PlayerNotification;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftPlayerService {
    private static final int REPORT_LIMIT = 50;
    private static final long MILLIS_PER_SECOND = 1000L;

    private final PlayerService playerService;
    private final PlayerRepository playerRepository;
    private final PunishmentRepository punishmentRepository;
    private final TicketRepository ticketRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final AccountLinkingService accountLinkingService;
    private final IssuerNameResolver issuerNameResolver;
    private final IpLookupService ipLookupService;
    private final AddressForwardingMonitor forwardingMonitor;
    private final MinecraftIssuerAuthorizer issuerAuthorizer;
    private final Clock clock;

    public PlayerLoginResult login(
        Server server,
        UUID playerUuid,
        String requestedUsername,
        @Nullable String reportedIp,
        @Nullable String requestedSkinHash,
        @Nullable String serverName
    ) {
        String ip = IpAddresses.literalOrNull(reportedIp);
        String username = PlayerColumns.storedUsername(requestedUsername);
        String skinHash = PlayerColumns.storedSkinHash(requestedSkinHash);
        Instant now = clock.instant();
        Optional<IpLookup> knownAddress = ip == null ? Optional.empty() : ipLookupService.known(ip);
        LoginRecord record = playerRepository.recordLogin(new LoginUpdate(
            playerUuid.toString(), username, ip, knownAddress.map(lookup -> LookupGeo.of(lookup.facts())).orElse(null),
            serverName, skinHash, now));
        forwardingMonitor.observeLogin(ip, serverName);
        accountLinkingService.linkNewMatches(record.playerId(), now);
        if (ip != null) {
            ipLookupService.refreshWhenStale(ip, knownAddress);
        }

        Player loaded = loggedInPlayer(record.playerId(), playerUuid, username, now);
        Player lifted = reloadedIfChanged(loaded, username,
            punishmentLifecycleService.checkRestrictionAutoPardons(server, loaded, username, skinHash));
        Player promoted = reloadedIfChanged(lifted, username,
            punishmentLifecycleService.promoteUnstartedPunishments(server, lifted));
        Player player = reloadedIfChanged(promoted, username,
            punishmentLifecycleService.enforceAltBlockingBans(server, promoted));

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        List<SimplePunishmentView> activePunishments = toActivePunishmentViews(server, deliveredPunishments(player, types), types);
        if (refusesLogin(activePunishments)) {
            playerRepository.withdrawRefusedLogin(record.playerId(), now);
        }
        return new PlayerLoginResult(
            record.created() ? 201 : 200,
            activePunishments,
            pendingNotifications(record.playerId(), now),
            pendingStatWipes(player, playerUuid, username));
    }

    private static boolean refusesLogin(List<SimplePunishmentView> activePunishments) {
        return activePunishments.stream().anyMatch(punishment -> ResolvedCategory.BAN.name().equals(punishment.category()));
    }

    private Player loggedInPlayer(String playerId, UUID playerUuid, String username, Instant now) {
        return Player.builder()
            .id(playerId)
            .minecraftUuid(playerUuid)
            .usernames(new ArrayList<>(List.of(new UsernameEntry(username, now))))
            .punishments(new ArrayList<>(punishmentRepository.findEnforcementRelevant(playerId, now)))
            .build();
    }

    private Player reloadedIfChanged(Player player, String username, List<String> changedPunishmentIds) {
        return changedPunishmentIds.isEmpty()
            ? player
            : loggedInPlayer(player.getId(), player.getMinecraftUuid(), username, clock.instant());
    }

    private List<Punishment> deliveredPunishments(Player player, PunishmentTypeLookup types) {
        List<Punishment> delivered = new ArrayList<>();
        for (Punishment punishment : player.getPunishments()) {
            if (!statusCalculator.isPunishmentActive(punishment)) {
                continue;
            }
            PunishmentType punishmentType = types.live(punishment.getTypeOrdinal());
            if (punishmentType != null && punishmentType.isKick()) {
                continue;
            }
            delivered.add(punishment);
        }
        for (Punishment punishment : player.getPunishments()) {
            if (punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL && punishment.getStarted() == null
                && !punishment.hasEndedEnforcement()) {
                delivered.add(punishment);
            }
        }
        return delivered;
    }

    private List<SimplePunishmentView> toActivePunishmentViews(Server server, List<Punishment> punishments,
                                                               PunishmentTypeLookup types) {
        if (punishments.isEmpty()) {
            return List.of();
        }
        Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPunishments(server, punishments);
        return deduplicateActivePunishments(punishments.stream()
            .map(punishment -> PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, resolvedIssuers))
            .toList());
    }

    private List<SimplePunishmentView> deduplicateActivePunishments(List<SimplePunishmentView> punishments) {
        Map<String, SimplePunishmentView> oldestByCategory = new LinkedHashMap<>();
        List<SimplePunishmentView> result = new ArrayList<>();

        for (SimplePunishmentView punishment : punishments) {
            String category = punishment.category();
            if (ResolvedCategory.BAN.name().equals(category) || ResolvedCategory.MUTE.name().equals(category)) {
                SimplePunishmentView existing = oldestByCategory.get(category);
                if (existing == null || punishment.issuedAt() < existing.issuedAt()) {
                    oldestByCategory.put(category, punishment);
                }
            } else {
                result.add(punishment);
            }
        }

        result.addAll(oldestByCategory.values());
        return result;
    }

    private List<Map<String, Object>> pendingNotifications(String playerId, Instant now) {
        return playerRepository.findNotifications(List.of(playerId), now)
            .getOrDefault(playerId, List.of())
            .stream()
            .map(PlayerNotification::payload)
            .toList();
    }

    private List<Map<String, Object>> pendingStatWipes(Player player, UUID playerUuid, String username) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Punishment punishment : player.getPunishments()) {
            if (!statusCalculator.isStatWipeDue(punishment)) {
                continue;
            }

            pending.add(Map.of(
                "minecraftUuid", playerUuid.toString(),
                "username", username,
                "punishmentId", punishment.getId()
            ));
        }
        return pending;
    }

    private Optional<Player> findPlayerByUuid(String uuid, PlayerParts... parts) {
        return playerRepository.findByMinecraftUuid(UuidUtils.normalize(uuid), parts);
    }

    public SimpleActionResult disconnect(Server server, String minecraftUuid) {
        playerRepository.markOffline(UuidUtils.normalize(minecraftUuid), clock.instant());
        return new SimpleActionResult(true);
    }

    public SimpleActionResult updateServer(Server server, String minecraftUuid, String serverName) {
        playerRepository.updateLastServer(UuidUtils.normalize(minecraftUuid), serverName);
        return new SimpleActionResult(true);
    }

    public OnlinePlayersResult getOnlinePlayers(Server server, OnlinePlayerPage page) {
        List<Player> found = playerRepository.findOnline(page.afterPlayerId(), page.size() + 1);
        boolean hasMore = found.size() > page.size();
        List<Player> shown = hasMore ? found.subList(0, page.size()) : found;
        Instant now = clock.instant();
        List<Map<String, Object>> players = shown
            .stream()
            .map(player -> {
                long totalPlaytimeMs = player.data().playtimeSecondsAt(now) * MILLIS_PER_SECOND;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("uuid", player.getMinecraftUuid().toString());
                entry.put("username", PlayerDataUtils.extractLatestUsername(player.getUsernames()));
                entry.put("joinedAt", player.data().lastLogin());
                entry.put("totalPlaytimeMs", totalPlaytimeMs);
                return entry;
            })
            .toList();

        return new OnlinePlayersResult(players, hasMore ? shown.getLast().getId() : null);
    }

    public CreateNoteResult createNote(Server server, String uuid, PlayerNoteKind kind, String text, @Nullable String issuerName,
                                       MinecraftIssuer issuer) {
        issuerAuthorizer.requirePermission(server, issuer, PlayerNotePermission.requiredFor(kind));
        Player player = findPlayerByUuid(uuid).orElse(null);
        if (player == null) {
            return new CreateNoteResult.NotFound("Player not found");
        }

        playerRepository.addNote(player.getId(), NoteEntry.builder()
            .id(EntityIds.next())
            .text(text)
            .kind(kind)
            .date(clock.instant())
            .issuerName(issuer.isConsole() ? issuerName : null)
            .issuerId(issuer.staffId())
            .build());

        return new CreateNoteResult.Created("Note added");
    }

    public AcknowledgeResult acknowledgeNotifications(Server server, AcknowledgeNotificationsRequest request) {
        return acknowledgeNotifications(server, request.playerUuid(), request.notificationIds(), request.acknowledgedAt());
    }

    public AcknowledgeResult acknowledgeNotifications(
        Server server,
        String playerUuid,
        List<String> notificationIds,
        @Nullable String acknowledgedAt
    ) {
        Player player = findPlayerByUuid(playerUuid).orElse(null);
        if (player == null) {
            return new AcknowledgeResult("No player found, nothing to acknowledge");
        }

        int acknowledged = playerRepository.acknowledgeNotifications(player.getId(), notificationIds);
        return new AcknowledgeResult("Acknowledged " + acknowledged + " notification(s)");
    }

    public PaginatedPunishmentsResult getPlayerPunishments(Server server, String uuid, int page, int limit) {
        Player player = findPlayerByUuid(uuid, PlayerParts.PUNISHMENTS).orElse(null);
        if (player == null) {
            return new PaginatedPunishmentsResult.NotFound("Player not found");
        }

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPlayers(server, List.of(player));

        List<PunishmentView> allPunishments = player.getPunishments()
            .stream()
            .sorted(Comparator.comparing(Punishment::getIssued).thenComparing(Punishment::getId).reversed())
            .map(p -> PunishmentMapper.toPunishmentView(p, types, resolvedIssuers))
            .toList();

        PaginationHelper.PageResult<PunishmentView> result = PaginationHelper.paginate(allPunishments, page, limit);
        return new PaginatedPunishmentsResult.Found(result.items(), result.totalCount(), result.page(), result.hasMore());
    }

    public PaginatedNotesResult getPlayerNotes(Server server, String uuid, int page, int limit) {
        Player player = findPlayerByUuid(uuid, PlayerParts.NOTES).orElse(null);
        if (player == null) {
            return new PaginatedNotesResult.NotFound("Player not found");
        }

        List<Map<String, Object>> allNotes = player.getNotes()
            .stream()
            .sorted((a, b) -> b.getDate().compareTo(a.getDate()))
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

        PaginationHelper.PageResult<Map<String, Object>> result = PaginationHelper.paginate(allNotes, page, limit);
        return new PaginatedNotesResult.Found(result.items(), result.totalCount(), result.page(), result.hasMore());
    }

    public PlayerReportsResult getPlayerReports(Server server, String uuid) {
        List<Map<String, Object>> reports = ticketRepository.findReportedPlayerTickets(UuidUtils.normalize(uuid), REPORT_LIMIT)
            .stream()
            .map(ticket -> {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("id", ticket.getId());
                report.put("type", ticket.getType() != null ? ticket.getType().getId() : null);
                report.put("reporterName", ticket.getCreatorName());
                report.put("reporterUuid", ticket.getCreatorUuid());
                report.put("subject", ticket.getSubject());
                report.put("status", ticket.getStatus() != null ? ticket.getStatus().getId() : null);
                report.put("priority", ticket.getPriority() != null ? ticket.getPriority().getId() : null);
                report.put("createdAt", ticket.getCreated());
                return report;
            })
            .toList();

        return new PlayerReportsResult(reports);
    }

    public PardonResult pardonPlayer(Server server, String playerName, @Nullable String punishmentType,
                                     @Nullable String issuerName, MinecraftIssuer issuer, @Nullable String reason) {
        issuerAuthorizer.requirePermission(server, issuer, MinecraftModification.PARDON.permission());
        Player player = playerService.findBestByUsername(server, playerName, PlayerParts.PUNISHMENTS).orElse(null);
        if (player == null) {
            return new PardonResult.PlayerNotFound("Player not found");
        }

        ResolvedCategory expected = PardonTargets.categoryOf(punishmentType);
        List<Punishment> targets = player.getPunishments().stream()
            .filter(punishment -> !punishment.hasEndedEnforcement())
            .filter(punishment -> statusCalculator.isPunishmentActive(punishment) || punishment.hasUnstartedStatus())
            .filter(punishment -> PardonTargets.matches(punishment, expected))
            .toList();

        int pardoned = targets.isEmpty()
                       ? 0
                       : punishmentLifecycleService.pardonPunishments(server, player, targets, issuerName, issuer.staffId(),
                           reason == null ? null : CappedText.of(reason, RequestValidationLimits.PLAYER_MODIFICATION_REASON_MAX_LENGTH));

        return new PardonResult.Pardoned(
            pardoned > 0,
            pardoned,
            pardoned > 0
                ? "Pardoned " + pardoned + " punishment(s)"
                : "No punishments found to pardon"
        );
    }
}
