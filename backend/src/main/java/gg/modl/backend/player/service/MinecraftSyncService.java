package gg.modl.backend.player.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncDataView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.player.repository.PlayerNotification;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.infrastructure.exception.ValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinecraftSyncService {
    private static final long DEFAULT_SYNC_WINDOW_SECONDS = 30;
    static final Duration COMMIT_OVERLAP = Duration.ofSeconds(10);
    static final int MAX_ONLINE_PLAYERS = 10_000;

    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final PlayerRepository playerRepository;
    private final PunishmentRepository punishmentRepository;
    private final StaffRepository staffRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final MinecraftChatLogService minecraftChatLogService;
    private final IssuerNameResolver issuerNameResolver;
    private final SyncStaffEventService syncStaffEventService;
    private final SyncActiveStaffService syncActiveStaffService;
    private final PublicUrls publicUrls;

    public SyncResult sync(
        Server server,
        @Nullable String lastSyncTimestamp,
        List<OnlinePlayerInput> onlinePlayers,
        @Nullable String serverName,
        List<ChatLogInput> chatLogs,
        List<CommandLogInput> commandLogs
    ) {
        Instant now = Instant.ofEpochMilli(clock.millis());
        Set<String> onlineUuids = collectOnlineUuids(onlinePlayers);

        markAbsentPlayersOffline(onlineUuids, serverName);

        Instant lastSync = parseLastSync(lastSyncTimestamp, now);

        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        List<SyncPunishmentEntry> pendingPunishments = new ArrayList<>();
        List<SyncPunishmentEntry> recentlyModifiedPunishments = new ArrayList<>();
        List<Map<String, Object>> playerNotifications = new ArrayList<>();

        List<Player> players = onlineUuids.isEmpty() ? List.of() : playerRepository.findByMinecraftUuids(onlineUuids);
        if (!players.isEmpty()) {
            loadSyncPunishments(players, lastSync, now);
            promoteWaitingPunishments(server, players, lastSync, now);

            Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPlayers(server, players);
            Map<String, List<PlayerNotification>> notifications =
                playerRepository.findNotifications(players.stream().map(Player::getId).toList(), now);

            for (Player player : players) {
                collectPlayerPunishments(player, types, lastSync, resolvedIssuers, pendingPunishments, recentlyModifiedPunishments);
                collectPlayerNotifications(player, notifications.getOrDefault(player.getId(), List.of()), playerNotifications);
            }
        }

        pendingPunishments = deduplicatePendingPunishments(pendingPunishments);
        List<Map<String, Object>> staffNotifications = syncStaffEventService.collectStaffEvents(server, lastSync, types, recentlyModifiedPunishments);

        Map<String, String> onlinePlayerIps = new HashMap<>();
        if (onlinePlayers != null) {
            for (OnlinePlayerInput onlinePlayer : onlinePlayers) {
                if (onlinePlayer.uuid() != null && onlinePlayer.ipAddress() != null) {
                    onlinePlayerIps.put(UuidUtils.normalize(onlinePlayer.uuid()), onlinePlayer.ipAddress());
                }
            }
        }

        List<Map<String, Object>> activeStaffMembers = syncActiveStaffService.getActiveStaffMembers(server, onlinePlayerIps);

        submitLogs(server, chatLogs, commandLogs);

        Long staffPermissionsUpdatedAt = server.getStaffPermissionsUpdatedAt() != null
            ? server.getStaffPermissionsUpdatedAt().toEpochMilli()
            : null;
        Long punishmentTypesUpdatedAt = server.getPunishmentTypesUpdatedAt() != null
            ? server.getPunishmentTypesUpdatedAt().toEpochMilli()
            : null;
        List<Map<String, Object>> staff2faVerifications = collectStaff2faVerifications();

        SyncDataView data = new SyncDataView(
            pendingPunishments,
            List.of(),
            recentlyModifiedPunishments,
            playerNotifications,
            staffNotifications,
            activeStaffMembers,
            List.of(),
            staffPermissionsUpdatedAt,
            punishmentTypesUpdatedAt,
            staff2faVerifications,
            publicUrls.panel()
        );

        return new SyncResult(watermark(lastSync, now).toString(), data);
    }

    static Instant watermark(Instant lastSync, Instant now) {
        Instant overlapped = now.minus(COMMIT_OVERLAP);
        return overlapped.isAfter(lastSync) ? overlapped : lastSync;
    }

    private void loadSyncPunishments(List<Player> players, Instant lastSync, Instant now) {
        Map<String, List<Punishment>> byPlayerId =
            punishmentRepository.findSyncRelevant(players.stream().map(Player::getId).toList(), lastSync, now);
        for (Player player : players) {
            player.setPunishments(new ArrayList<>(byPlayerId.getOrDefault(player.getId(), List.of())));
        }
    }

    private void promoteWaitingPunishments(Server server, List<Player> players, Instant lastSync, Instant now) {
        List<Player> promotedPlayers = new ArrayList<>();
        for (Player player : players) {
            if (!punishmentLifecycleService.promoteUnstartedPunishments(server, player).isEmpty()) {
                promotedPlayers.add(player);
            }
        }
        if (!promotedPlayers.isEmpty()) {
            loadSyncPunishments(promotedPlayers, lastSync, now);
        }
    }

    private void collectPlayerPunishments(
        Player player,
        PunishmentTypeLookup types,
        Instant lastSync,
        Map<String, String> resolvedIssuers,
        List<SyncPunishmentEntry> pendingPunishments,
        List<SyncPunishmentEntry> recentlyModifiedPunishments
    ) {
        String uuid = player.getMinecraftUuid().toString();
        String username = PlayerDataUtils.extractLatestUsername(player.getUsernames());

        Set<String> categoriesWithActiveStarted = new HashSet<>();
        Map<String, Punishment> oldestUnstartedPerCategory = new LinkedHashMap<>();
        Set<String> endedCategories = new HashSet<>();

        for (Punishment punishment : player.getPunishments()) {
            boolean active = statusCalculator.isPunishmentActive(punishment);
            String category = statusCalculator.getEffectiveCategory(punishment).displayName();

            boolean recentlyModified = punishment.getModifications()
                .stream()
                .anyMatch(mod -> mod.date() != null && mod.date().isAfter(lastSync));
            if (recentlyModified) {
                recentlyModifiedPunishments.add(new SyncPunishmentEntry(
                    uuid, username,
                    PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, resolvedIssuers)));
            }

            boolean recentlyEnded = punishment.getModifications()
                .stream()
                .anyMatch(mod -> mod.date() != null
                                 && mod.date().isAfter(lastSync)
                                 && PunishmentModificationType.endsEnforcement(mod.type()));
            if (recentlyEnded && category != null) {
                endedCategories.add(category);
            }

            if (punishment.getTypeOrdinal() == 0 && punishment.getStarted() == null) {
                if (!punishment.hasEndedEnforcement()) {
                    pendingPunishments.add(new SyncPunishmentEntry(
                        uuid, username,
                        PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, resolvedIssuers)));
                }
                continue;
            }

            if (!active) {
                continue;
            }

            if (punishment.getStarted() != null
                && punishment.getIssued() != null
                && punishment.getIssued().isAfter(lastSync)
                && category != null) {
                pendingPunishments.add(new SyncPunishmentEntry(
                    uuid, username,
                    PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, resolvedIssuers)));
            }

            if (category != null && punishment.getStarted() != null) {
                categoriesWithActiveStarted.add(category);
            } else if (category != null && punishment.getStarted() == null) {
                Punishment existing = oldestUnstartedPerCategory.get(category);
                if (existing == null
                    || (punishment.getIssued() != null
                        && (existing.getIssued() == null
                            || punishment.getIssued().isBefore(existing.getIssued())))) {
                    oldestUnstartedPerCategory.put(category, punishment);
                }
            }
        }

        for (Map.Entry<String, Punishment> entry : oldestUnstartedPerCategory.entrySet()) {
            if (!categoriesWithActiveStarted.contains(entry.getKey())) {
                pendingPunishments.add(new SyncPunishmentEntry(
                    uuid, username,
                    PunishmentMapper.toSimplePunishment(entry.getValue(), types, statusCalculator, resolvedIssuers)));
            }
        }

        if (!endedCategories.isEmpty()) {
            for (Punishment punishment : player.getPunishments()) {
                if (!statusCalculator.isPunishmentActive(punishment) || punishment.getStarted() == null) {
                    continue;
                }

                String category = statusCalculator.getEffectiveCategory(punishment).displayName();
                if (category != null && endedCategories.contains(category)) {
                    pendingPunishments.add(new SyncPunishmentEntry(
                        uuid, username,
                        PunishmentMapper.toSimplePunishment(punishment, types, statusCalculator, resolvedIssuers)));
                    endedCategories.remove(category);
                }
            }
        }
    }

    private void collectPlayerNotifications(Player player, List<PlayerNotification> pending,
                                            List<Map<String, Object>> playerNotifications) {
        String uuid = player.getMinecraftUuid().toString();
        for (PlayerNotification notification : pending) {
            Map<String, Object> payload = new HashMap<>(notification.payload());
            payload.put("id", notification.id());
            payload.put("targetPlayerUuid", uuid);
            playerNotifications.add(payload);
        }
    }

    private @Nullable List<Map<String, Object>> collectStaff2faVerifications() {
        try {
            List<Staff> pendingStaff = staffRepository.findWithPendingTwoFactorDelivery();
            if (!pendingStaff.isEmpty()) {
                List<Map<String, Object>> verifications = pendingStaff.stream()
                    .filter(staff -> staff.getAssignedMinecraftUuid() != null && staff.getTwoFactorSessionIp() != null)
                    .map(staff -> Map.<String, Object>of(
                        "minecraftUuid", staff.getAssignedMinecraftUuid(), "ip", staff.getTwoFactorSessionIp()))
                    .toList();
                List<String> readStaffIds = pendingStaff.stream().map(Staff::getId).toList();
                unitOfWork.write(() -> staffRepository.clearPendingTwoFactorDelivery(readStaffIds));
                return verifications;
            }
        } catch (Exception e) {
            log.warn("Failed to process 2FA verifications during sync", e);
        }
        return null;
    }

    public void applyPresence(Server server, List<OnlinePlayerInput> onlinePlayers, @Nullable String serverName) {
        markAbsentPlayersOffline(collectOnlineUuids(onlinePlayers), serverName);
    }

    private void markAbsentPlayersOffline(Set<String> onlineUuids, @Nullable String serverName) {
        String scope = serverName == null || serverName.isBlank() ? null : serverName;
        if (findAbsentPlayers(onlineUuids, scope).isEmpty()) {
            return;
        }
        unitOfWork.write(() -> playerRepository.markOfflineAll(findAbsentPlayers(onlineUuids, scope), clock.instant()));
    }

    private Set<String> findAbsentPlayers(Set<String> onlineUuids, @Nullable String scope) {
        Set<String> absent = new HashSet<>();
        for (String uuid : playerRepository.findOnlineUuids(scope)) {
            if (!onlineUuids.contains(uuid)) {
                absent.add(uuid);
            }
        }
        return absent;
    }

    public void submitLogs(Server server, List<ChatLogInput> chatLogs, List<CommandLogInput> commandLogs) {
        if (chatLogs != null && !chatLogs.isEmpty()) {
            minecraftChatLogService.submitChatLogs(server, chatLogs.stream()
                .map(entry -> new MinecraftChatLogService.ChatLogCommand(
                    entry.uuid(),
                    entry.username(),
                    entry.message(),
                    entry.timestamp(),
                    entry.server()
                ))
                .toList());
        }
        if (commandLogs != null && !commandLogs.isEmpty()) {
            minecraftChatLogService.submitCommandLogs(server, commandLogs.stream()
                .map(entry -> new MinecraftChatLogService.CommandLogCommand(
                    entry.uuid(),
                    entry.username(),
                    entry.command(),
                    entry.timestamp(),
                    entry.server()
                ))
                .toList());
        }
    }

    private Instant parseLastSync(@Nullable String lastSyncTimestamp, Instant now) {
        Instant fallback = now.minusSeconds(DEFAULT_SYNC_WINDOW_SECONDS);
        if (lastSyncTimestamp == null || lastSyncTimestamp.isBlank()) {
            return fallback;
        }
        try {
            return withinStorableRange(Instant.parse(lastSyncTimestamp));
        } catch (DateTimeParseException e) {
            log.warn("Invalid lastSyncTimestamp during sync; falling back to default window");
            return fallback;
        }
    }

    private static Instant withinStorableRange(Instant value) {
        if (value.isBefore(Instant.EPOCH)) {
            return Instant.EPOCH;
        }
        return value.isAfter(PunishmentState.MAX) ? PunishmentState.MAX : value;
    }

    private Set<String> collectOnlineUuids(List<OnlinePlayerInput> onlinePlayers) {
        Set<String> onlineUuids = new HashSet<>();
        if (onlinePlayers != null && onlinePlayers.size() > MAX_ONLINE_PLAYERS) {
            throw new ValidationException("A sync can list at most " + MAX_ONLINE_PLAYERS + " online players", "INVALID_ARGUMENT");
        }
        if (onlinePlayers != null) {
            for (OnlinePlayerInput onlinePlayer : onlinePlayers) {
                if (onlinePlayer.uuid() != null) {
                    onlineUuids.add(UuidUtils.normalize(onlinePlayer.uuid()));
                }
            }
        }
        return onlineUuids;
    }

    private List<SyncPunishmentEntry> deduplicatePendingPunishments(List<SyncPunishmentEntry> punishments) {
        Map<String, SyncPunishmentEntry> oldestByPlayerCategory = new LinkedHashMap<>();
        List<SyncPunishmentEntry> result = new ArrayList<>();

        for (SyncPunishmentEntry entry : punishments) {
            SimplePunishmentView punishment = entry.punishment();
            String category = punishment.category();
            if (ResolvedCategory.BAN.name().equals(category) || ResolvedCategory.MUTE.name().equals(category)) {
                String key = entry.minecraftUuid() + "|" + category;
                SyncPunishmentEntry existing = oldestByPlayerCategory.get(key);
                if (existing == null) {
                    oldestByPlayerCategory.put(key, entry);
                } else if (punishment.issuedAt() < existing.punishment().issuedAt()) {
                    oldestByPlayerCategory.put(key, entry);
                }
            } else {
                result.add(entry);
            }
        }

        result.addAll(oldestByPlayerCategory.values());
        return result;
    }

    public record OnlinePlayerInput(String uuid, String username, String ipAddress) {
    }

    public record ChatLogInput(String uuid, String username, String message, long timestamp, String server) {
    }

    public record CommandLogInput(String uuid, String username, String command, long timestamp, String server) {
    }
}
