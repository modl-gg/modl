package gg.modl.minecraft.features.staffmode;

import gg.modl.minecraft.core.network.MemberAction;
import gg.modl.minecraft.core.network.MemberActionKind;
import gg.modl.minecraft.core.network.MemberActionOutcome;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.FeatureTask;
import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.config.StaffModeConfig.HotbarItem;
import gg.modl.minecraft.features.config.StaffModeConfig.ScoreboardConfig;
import gg.modl.minecraft.features.freeze.FrozenPlayers;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.staffmode.custody.BelongingsPort;
import gg.modl.minecraft.features.staffmode.custody.CustodyListener;
import gg.modl.minecraft.features.staffmode.custody.CustodyNotice;
import gg.modl.minecraft.features.staffmode.custody.StaffCustody;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class StaffModeCore {
    private static final int SCOREBOARD_MAX_LINE_LENGTH = 40;
    private static final int HOTBAR_MIN_SLOT = 0;
    private static final int HOTBAR_MAX_SLOT = 8;
    private static final long RESPAWN_SETTLE_TICKS = 1;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private static final String ACTION_TARGET_SELECTOR = "target_selector";
    private static final String ACTION_VANISH_TOGGLE = "vanish_toggle";
    private static final String ACTION_STAFF_MENU = "staff_menu";
    private static final String ACTION_RANDOM_TELEPORT = "random_teleport";
    private static final String ACTION_FREEZE_TARGET = "freeze_target";
    private static final String ACTION_STOP_TARGET = "stop_target";
    private static final String ACTION_INSPECT_TARGET = "inspect_target";
    private static final String ACTION_OPEN_INVENTORY = "open_inventory";
    private static final String ACTION_TELEPORT_TO_TARGET = "teleport_to_target";
    private static final String ACTION_HACKREPORT_TARGET = "hackreport_target";

    private static final Map<String, MemberActionKind> FORWARDED_ACTIONS = forwardedActions();
    private static final Set<MemberActionOutcome> CORE_UNREACHABLE =
            Collections.unmodifiableSet(EnumSet.of(MemberActionOutcome.NO_CORE, MemberActionOutcome.RATE_LIMITED));

    private final StaffModeConfig staffModeConfig;
    private final FeaturesLocaleManager localeManager;
    private final PluginLogger logger;
    private final FeatureScheduler scheduler;
    private final FrozenPlayers frozenPlayers;
    private final StaffModeOps ops;
    private final MemberActionSender actions;
    private final Supplier<String> serverName;
    private final StartingLocationReturns startingLocations;
    private final StaffCustody custody;

    private final Set<UUID> staffModeActive = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> targetByStaff = new ConcurrentHashMap<>();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> scoreboardActive = ConcurrentHashMap.newKeySet();
    private final Set<UUID> unreportedRefusals = ConcurrentHashMap.newKeySet();
    private final Map<String, Consumer<UUID>> localActions = buildLocalActions();

    private @Nullable FeatureTask scoreboardTimer;

    public StaffModeCore(StaffModeConfig staffModeConfig, FeaturesLocaleManager localeManager, PluginLogger logger,
                         FeatureScheduler scheduler, FrozenPlayers frozenPlayers, StaffModeOps ops,
                         BelongingsPort belongings, StaffModePersistence persistence, MemberActionSender actions,
                         Supplier<String> serverName) {
        this.staffModeConfig = staffModeConfig;
        this.localeManager = localeManager;
        this.logger = logger;
        this.scheduler = scheduler;
        this.frozenPlayers = frozenPlayers;
        this.ops = new GuardedStaffModeOps(ops, logger);
        this.actions = actions;
        this.serverName = serverName;
        this.custody = new StaffCustody(belongings, persistence.getCustody(), scheduler, logger, new CustodyEvents());
        this.startingLocations = new StartingLocationReturns(this.ops, custody::holdsBelongings, scheduler,
                persistence.getStartingLocations(), logger, System::currentTimeMillis);
    }

    public void start() {
        for (UUID uuid : ops.onlinePlayerUuids()) {
            scheduler.runForPlayer(uuid, () -> {
                if (ops.isOnline(uuid)) custody.playerFoundOnline(uuid);
            });
        }
        startingLocations.load(this::returnOnlinePlayers);
        scoreboardTimer = scheduler.runTimerAsync(this::tickScoreboards, 1, 1, TimeUnit.SECONDS);
    }

    public void shutdown() {
        FeatureTask timer = scoreboardTimer;
        if (timer != null) {
            timer.cancel();
            scoreboardTimer = null;
        }
        startingLocations.stop();
        custody.stop();
        Set<UUID> holdingCustody = new HashSet<>(staffModeActive);
        holdingCustody.addAll(custody.playersInCustody());
        for (UUID uuid : ops.onlinePlayerUuids()) {
            if (startingLocations.isPending(uuid)) holdingCustody.add(uuid);
        }
        List<UUID> unreturned = new ArrayList<>();
        for (UUID uuid : holdingCustody) {
            if (!releaseStaffMode(uuid)) unreturned.add(uuid);
        }
        if (!unreturned.isEmpty()) {
            logger.severe("[staff-mode] Belongings could not be returned before shutdown for " + unreturned
                    + "; modl keeps them and returns them when these players join again");
        }
        for (UUID uuid : new ArrayList<>(vanished)) {
            if (ops.isOnline(uuid)) {
                unvanish(uuid);
            }
        }
        scoreboardActive.clear();
        staffModeActive.clear();
        vanished.clear();
        targetByStaff.clear();
        ops.clearScoreboards();
        custody.close();
        startingLocations.close();
    }

    public boolean isInStaffMode(UUID uuid) {
        return staffModeActive.contains(uuid);
    }

    public boolean hasStaffInventory(UUID uuid) {
        return staffModeActive.contains(uuid) && custody.hasTakenBelongings(uuid);
    }

    public boolean holdsBelongings(UUID uuid) {
        return custody.holdsBelongings(uuid);
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    public @Nullable UUID targetOf(UUID staff) {
        return targetByStaff.get(staff);
    }

    public Set<UUID> sessionPlayers() {
        Set<UUID> players = new HashSet<>(staffModeActive);
        players.addAll(vanished);
        players.addAll(targetByStaff.keySet());
        players.addAll(unreportedRefusals);
        return players;
    }

    public boolean hasUnreportedRefusal(UUID uuid) {
        return unreportedRefusals.contains(uuid);
    }

    public void enterStaffMode(UUID uuid) {
        if (unreportedRefusals.contains(uuid)) {
            reportRefusal(uuid);
            return;
        }
        if (!staffModeActive.add(uuid)) return;

        scheduler.runForPlayer(uuid, () -> {
            if (ops.isOnline(uuid) && staffModeActive.contains(uuid)) custody.hold(uuid);
        });
    }

    private void setUpOrRollBack(UUID uuid) {
        boolean applied = false;
        try {
            ops.currentLocation(uuid).ifPresent(location -> startingLocations.remember(uuid, location));
            applyStaffModeSetup(uuid);
            applied = true;
        } finally {
            if (!applied) {
                staffModeActive.remove(uuid);
                releaseStaffMode(uuid);
                refuse(uuid);
            }
        }
    }

    public void exitStaffMode(UUID uuid) {
        if (unreportedRefusals.remove(uuid) && !staffModeActive.contains(uuid)) return;
        staffModeActive.remove(uuid);
        targetByStaff.remove(uuid);

        if (!ops.isOnline(uuid)) {
            scoreboardActive.remove(uuid);
            return;
        }

        scheduler.runForPlayer(uuid, () -> {
            if (!ops.isOnline(uuid)) return;
            releaseStaffMode(uuid);
            hideVanishedPlayersFrom(uuid);
        });
    }

    public void applyVanish(UUID uuid, boolean vanish) {
        boolean changed = vanish ? vanished.add(uuid) : vanished.remove(uuid);
        if (!changed) return;

        scheduler.runForPlayer(uuid, () -> {
            if (!ops.isOnline(uuid)) return;
            refreshVisibility(uuid);
            if (hasStaffInventory(uuid)) {
                updateVanishHotbarItem(uuid, vanished.contains(uuid));
            }
        });
    }

    public void setTarget(UUID staff, UUID target) {
        if (target.equals(targetByStaff.put(staff, target))) return;
        showTarget(staff, target);
    }

    public void clearTarget(UUID staff) {
        if (targetByStaff.remove(staff) == null) return;

        scheduler.runForPlayer(staff, () -> {
            if (!ops.isOnline(staff) || !hasStaffInventory(staff)) return;
            setupHotbar(staff, staffModeConfig.getStaffHotbar());
            refreshScoreboard(staff);
        });
    }

    private void showTarget(UUID staff, UUID target) {
        scheduler.runForPlayer(staff, () -> {
            if (!ops.isOnline(staff) || !hasStaffInventory(staff)) return;
            setupHotbar(staff, staffModeConfig.getTargetHotbar());
            refreshScoreboard(staff);
            if (ops.isOnline(target)) {
                ops.teleportToPlayer(staff, target);
            }
        });
    }

    public void handleHotbarAction(UUID staffUuid, int heldSlot) {
        if (!hasStaffInventory(staffUuid)) return;
        HotbarItem item = getActiveHotbar(staffUuid).get(heldSlot);
        if (item != null && item.getAction() != null && !item.getAction().isEmpty()) {
            executeAction(staffUuid, item);
        }
    }

    public void handleTargetSelect(UUID staffUuid, int heldSlot, UUID clickedUuid) {
        if (!hasStaffInventory(staffUuid)) return;
        HotbarItem item = getActiveHotbar(staffUuid).get(heldSlot);
        if (item != null && ACTION_TARGET_SELECTOR.equals(item.getAction())) {
            forward(staffUuid, MemberAction.playerAction(MemberActionKind.SELECT_TARGET, staffUuid, clickedUuid));
        }
    }

    public void handlePlayerJoin(UUID uuid) {
        if (!hasStaffInventory(uuid)) custody.playerJoined(uuid);

        if (!staffModeActive.contains(uuid)) {
            hideVanishedPlayersFrom(uuid);
        }
        if (vanished.contains(uuid)) {
            refreshVisibility(uuid);
        }
    }

    public void handlePlayerQuit(UUID uuid) {
        unreportedRefusals.remove(uuid);
        staffModeActive.remove(uuid);
        removeActiveScoreboard(uuid);
        if (vanished.contains(uuid)) unvanish(uuid);
        custody.playerLeft(uuid);
        targetByStaff.remove(uuid);
        discardScoreboard(uuid);
    }

    public void handlePlayerRespawn(UUID uuid) {
        scheduler.runForPlayerLater(uuid, () -> {
            if (!ops.isOnline(uuid)) return;
            if (hasStaffInventory(uuid)) {
                ops.setGameMode(uuid, StaffGameMode.CREATIVE);
                setupHotbar(uuid, getActiveHotbar(uuid));
            } else {
                continueAfterCustody(uuid);
            }
        }, RESPAWN_SETTLE_TICKS);
    }

    private void applyStaffModeSetup(UUID uuid) {
        ops.setGameMode(uuid, StaffGameMode.CREATIVE);
        showVanishedPlayersTo(uuid);

        setupHotbar(uuid, getActiveHotbar(uuid));
        createScoreboard(uuid);
    }

    private void setupHotbar(UUID uuid, Map<Integer, HotbarItem> hotbar) {
        ops.clearInventory(uuid);
        boolean isVanished = vanished.contains(uuid);

        for (Map.Entry<Integer, HotbarItem> entry : hotbar.entrySet()) {
            int slot = entry.getKey();
            if (slot < HOTBAR_MIN_SLOT || slot > HOTBAR_MAX_SLOT) continue;
            HotbarItem item = entry.getValue();
            boolean useToggle = ACTION_VANISH_TOGGLE.equals(item.getAction())
                    && !isVanished && item.getToggleItem() != null;
            applyHotbarSlot(uuid, slot, item, useToggle);
        }
    }

    private void updateVanishHotbarItem(UUID uuid, boolean nowVanished) {
        for (Map.Entry<Integer, HotbarItem> entry : getActiveHotbar(uuid).entrySet()) {
            if (ACTION_VANISH_TOGGLE.equals(entry.getValue().getAction())) {
                boolean useToggle = !nowVanished && entry.getValue().getToggleItem() != null;
                applyHotbarSlot(uuid, entry.getKey(), entry.getValue(), useToggle);
                return;
            }
        }
    }

    private void applyHotbarSlot(UUID uuid, int slot, HotbarItem item, boolean useToggle) {
        String materialId = useToggle && item.getToggleItem() != null ? item.getToggleItem() : item.getItem();
        String name = useToggle && item.getToggleName() != null ? item.getToggleName() : item.getName();
        List<String> lore = useToggle && !item.getToggleLore().isEmpty() ? item.getToggleLore() : item.getLore();

        String displayName = localeManager.colorize(name);
        List<String> colorizedLore = lore.stream().map(localeManager::colorize).collect(Collectors.toList());
        ops.setHotbarSlot(uuid, slot, materialId, displayName, colorizedLore);
    }

    private Map<Integer, HotbarItem> getActiveHotbar(UUID uuid) {
        return targetByStaff.containsKey(uuid) ? staffModeConfig.getTargetHotbar() : staffModeConfig.getStaffHotbar();
    }

    private void refreshVisibility(UUID uuid) {
        boolean hidden = vanished.contains(uuid);
        for (UUID online : ops.onlinePlayerUuids()) {
            if (online.equals(uuid)) continue;
            if (!hidden) ops.showPlayer(online, uuid);
            else if (!staffModeActive.contains(online)) ops.hidePlayer(online, uuid);
        }
    }

    private void unvanish(UUID uuid) {
        vanished.remove(uuid);
        for (UUID online : ops.onlinePlayerUuids()) {
            if (!online.equals(uuid)) {
                ops.showPlayer(online, uuid);
            }
        }
    }

    private void hideVanishedPlayersFrom(UUID viewer) {
        for (UUID vanishedUuid : vanished) {
            if (!vanishedUuid.equals(viewer) && ops.isOnline(vanishedUuid)) {
                ops.hidePlayer(viewer, vanishedUuid);
            }
        }
    }

    private void showVanishedPlayersTo(UUID viewer) {
        for (UUID vanishedUuid : vanished) {
            if (!vanishedUuid.equals(viewer) && ops.isOnline(vanishedUuid)) {
                ops.showPlayer(viewer, vanishedUuid);
            }
        }
    }

    private @Nullable UUID resolveTarget(UUID staffUuid) {
        UUID targetUuid = targetByStaff.get(staffUuid);
        if (targetUuid == null) return null;
        return ops.isOnline(targetUuid) ? targetUuid : null;
    }

    private static Map<String, MemberActionKind> forwardedActions() {
        Map<String, MemberActionKind> forwarded = new HashMap<>();
        forwarded.put(ACTION_VANISH_TOGGLE, MemberActionKind.TOGGLE_VANISH);
        forwarded.put(ACTION_STAFF_MENU, MemberActionKind.OPEN_STAFF_MENU);
        forwarded.put(ACTION_FREEZE_TARGET, MemberActionKind.TOGGLE_FREEZE_TARGET);
        forwarded.put(ACTION_STOP_TARGET, MemberActionKind.CLEAR_TARGET);
        forwarded.put(ACTION_INSPECT_TARGET, MemberActionKind.INSPECT_TARGET);
        forwarded.put(ACTION_HACKREPORT_TARGET, MemberActionKind.HACKREPORT_TARGET);
        return Collections.unmodifiableMap(forwarded);
    }

    private Map<String, Consumer<UUID>> buildLocalActions() {
        Map<String, Consumer<UUID>> handlers = new HashMap<>();
        handlers.put(ACTION_RANDOM_TELEPORT, this::handleRandomTeleport);
        handlers.put(ACTION_OPEN_INVENTORY, this::handleOpenInventory);
        handlers.put(ACTION_TELEPORT_TO_TARGET, this::handleTeleportToTarget);
        return handlers;
    }

    private void executeAction(UUID uuid, HotbarItem item) {
        MemberActionKind forwarded = FORWARDED_ACTIONS.get(item.getAction());
        if (forwarded != null) {
            forward(uuid, MemberAction.playerAction(forwarded, uuid, null));
            return;
        }
        Consumer<UUID> handler = localActions.get(item.getAction());
        if (handler != null) {
            handler.accept(uuid);
        }
    }

    private void refuse(UUID uuid) {
        unreportedRefusals.add(uuid);
        reportRefusal(uuid);
    }

    private void reportRefusal(UUID uuid) {
        actions.send(MemberAction.playerAction(MemberActionKind.STAFF_MODE_REFUSED, uuid, null), outcome -> {
            if (outcome == MemberActionOutcome.ACCEPTED || outcome == MemberActionOutcome.INVALID) {
                unreportedRefusals.remove(uuid);
            }
        });
    }

    private void forward(UUID actor, MemberAction action) {
        actions.send(action, outcome -> {
            if (CORE_UNREACHABLE.contains(outcome)) {
                scheduler.runForPlayer(actor, () -> ops.sendMessage(actor, localeManager.getMessage("network.unavailable")));
            }
        });
    }

    private void handleRandomTeleport(UUID uuid) {
        List<UUID> candidates = new ArrayList<>();
        for (UUID online : ops.onlinePlayerUuids()) {
            if (!online.equals(uuid) && !staffModeActive.contains(online)) {
                candidates.add(online);
            }
        }

        if (candidates.isEmpty()) {
            ops.sendMessage(uuid, localeManager.getMessage("staff_mode.random_teleport.no_players"));
            return;
        }

        UUID target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        ops.teleportToPlayer(uuid, target);
        ops.sendMessage(uuid, localeManager.getMessage("staff_mode.random_teleport.teleported",
                mapOf("player", displayName(target))));
    }

    private void handleOpenInventory(UUID uuid) {
        UUID target = resolveTarget(uuid);
        if (target != null) {
            ops.openTargetInventory(uuid, target);
        }
    }

    private void handleTeleportToTarget(UUID uuid) {
        UUID target = resolveTarget(uuid);
        if (target != null) {
            ops.teleportToPlayer(uuid, target);
            ops.sendMessage(uuid, localeManager.getMessage("staff_mode.teleport.teleported",
                    mapOf("player", displayName(target))));
        } else {
            ops.sendMessage(uuid, localeManager.getMessage("staff_mode.teleport.target_offline"));
        }
    }

    private void returnOnlinePlayers() {
        for (UUID uuid : ops.onlinePlayerUuids()) {
            if (startingLocations.isPending(uuid)) scheduleReturn(uuid);
        }
    }

    private void continueAfterCustody(UUID uuid) {
        if (staffModeActive.contains(uuid)) {
            custody.hold(uuid);
        } else if (custody.release(uuid) && startingLocations.isPending(uuid)) {
            startingLocations.returnPlayer(uuid);
        }
    }

    private void scheduleReturn(UUID uuid) {
        scheduler.runForPlayer(uuid, () -> {
            if (ops.isOnline(uuid) && !staffModeActive.contains(uuid)) returnCustody(uuid);
        });
    }

    private boolean releaseStaffMode(UUID uuid) {
        removeActiveScoreboard(uuid);
        return returnCustody(uuid);
    }

    private void removeActiveScoreboard(UUID uuid) {
        if (scoreboardActive.remove(uuid)) ops.removeScoreboard(uuid);
    }

    private boolean returnCustody(UUID uuid) {
        if (!custody.release(uuid)) return false;
        startingLocations.returnPlayer(uuid);
        return true;
    }

    private void createScoreboard(UUID uuid) {
        ScoreboardConfig config = getScoreboardConfig(uuid);
        if (!config.isEnabled()) return;
        scoreboardActive.add(uuid);
        ops.createScoreboard(uuid, buildContent(uuid, config));
    }

    private void removeScoreboard(UUID uuid) {
        scoreboardActive.remove(uuid);
        ops.removeScoreboard(uuid);
    }

    private void refreshScoreboard(UUID uuid) {
        removeScoreboard(uuid);
        createScoreboard(uuid);
    }

    private void discardScoreboard(UUID uuid) {
        scoreboardActive.remove(uuid);
        ops.discardScoreboard(uuid);
    }

    private void tickScoreboards() {
        try {
            renderActiveScoreboards();
        } catch (RuntimeException | LinkageError e) {
            logger.warning("[staff-mode] Scoreboard tick failed", e);
        }
    }

    private void renderActiveScoreboards() {
        for (UUID uuid : scoreboardActive) {
            if (!ops.isOnline(uuid)) {
                discardScoreboard(uuid);
                continue;
            }
            scheduler.runForPlayer(uuid, () -> renderScoreboard(uuid));
        }
    }

    private void renderScoreboard(UUID uuid) {
        if (!scoreboardActive.contains(uuid)) return;
        if (!ops.isOnline(uuid)) {
            discardScoreboard(uuid);
            return;
        }
        ops.updateScoreboard(uuid, buildContent(uuid, getScoreboardConfig(uuid)));
    }

    private ScoreboardContent buildContent(UUID uuid, ScoreboardConfig config) {
        String title = localeManager.colorize(replacePlaceholders(config.getTitle(), uuid));

        List<String> configLines = config.getLines();
        List<ScoreboardContent.Line> lines = new ArrayList<>();
        int score = configLines.size();
        Set<String> usedEntries = new HashSet<>();

        for (String line : configLines) {
            String resolved = localeManager.colorize(replacePlaceholders(line, uuid));
            String entry = uniqueEntry(truncateColorSafe(resolved, SCOREBOARD_MAX_LINE_LENGTH), usedEntries);
            usedEntries.add(entry);
            lines.add(new ScoreboardContent.Line(entry, score--));
        }
        return new ScoreboardContent(title, lines);
    }

    private static String uniqueEntry(String resolved, Set<String> usedEntries) {
        String candidate = resolved;
        StringBuilder suffix = new StringBuilder();
        while (usedEntries.contains(candidate) && suffix.length() < SCOREBOARD_MAX_LINE_LENGTH) {
            suffix.append('§').append('r');
            candidate = truncateColorSafe(resolved, SCOREBOARD_MAX_LINE_LENGTH - suffix.length()) + suffix;
        }
        return candidate;
    }

    private static String truncateColorSafe(String s, int n) {
        if (s == null || s.length() <= n) return s;
        if (n > 0 && s.charAt(n - 1) == '§') return s.substring(0, n - 1);
        return s.substring(0, n);
    }

    private String replacePlaceholders(String line, UUID uuid) {
        boolean isVanished = vanished.contains(uuid);
        ScoreboardConfig config = getScoreboardConfig(uuid);

        String result = line
                .replace("{player_name}", displayName(uuid))
                .replace("{server}", serverName.get())
                .replace("{online}", String.valueOf(ops.onlinePlayerCount()))
                .replace("{max_players}", String.valueOf(ops.maxPlayerCount()))
                .replace("{date}", LocalDate.now(ZoneId.systemDefault()).format(DATE_FORMAT))
                .replace("{vanish}", isVanished ? config.getVanish() : "")
                .replace("{vanish_status}", scoreboardWord(isVanished ? "vanish_on" : "vanish_off"))
                .replace("{vanished}", scoreboardWord(isVanished ? "vanished" : "visible"))
                .replace("{staff_online}", String.valueOf(staffModeActive.size()));
        return replaceTargetPlaceholders(result, uuid);
    }

    private String replaceTargetPlaceholders(String result, UUID staffUuid) {
        UUID targetUuid = targetByStaff.get(staffUuid);

        if (targetUuid != null && ops.isOnline(targetUuid)) {
            return result
                    .replace("{target_name}", displayName(targetUuid))
                    .replace("{target_health}", String.format("%.1f", ops.playerHealth(targetUuid)))
                    .replace("{target_ping}", String.valueOf(ops.playerPing(targetUuid)))
                    .replace("{freeze_status}", scoreboardWord(frozenPlayers.isFrozen(targetUuid) ? "frozen_yes" : "frozen_no"));
        }

        String notAvailable = scoreboardWord("not_available");
        return result
                .replace("{target_name}", scoreboardWord(targetUuid != null ? "target_offline" : "no_target"))
                .replace("{target_health}", notAvailable)
                .replace("{target_ping}", notAvailable)
                .replace("{freeze_status}", notAvailable);
    }

    private String scoreboardWord(String key) {
        return localeManager.getMessage("staff_mode.scoreboard." + key);
    }

    private String displayName(UUID uuid) {
        String playerName = ops.playerName(uuid);
        return playerName != null ? playerName : localeManager.getMessage("staff_mode.unknown_player");
    }

    private ScoreboardConfig getScoreboardConfig(UUID uuid) {
        return targetByStaff.containsKey(uuid) ? staffModeConfig.getTargetScoreboard() : staffModeConfig.getStaffScoreboard();
    }

    private final class CustodyEvents implements CustodyListener {
        @Override
        public void custodyResolved(UUID player) {
            continueAfterCustody(player);
        }

        @Override
        public void belongingsTaken(UUID player) {
            if (!staffModeActive.contains(player)) {
                returnCustody(player);
                return;
            }
            setUpOrRollBack(player);
            UUID target = targetByStaff.get(player);
            if (target != null) showTarget(player, target);
        }

        @Override
        public void staffModeRefused(UUID player, CustodyNotice notice) {
            staffModeActive.remove(player);
            releaseStaffMode(player);
            notifyPlayer(player, notice);
            refuse(player);
        }

        @Override
        public void notifyPlayer(UUID player, CustodyNotice notice) {
            ops.sendMessage(player, localeManager.getMessage(notice.localeKey()));
        }
    }
}
