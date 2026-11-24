package gg.modl.minecraft.spigot.features.handler;

import com.github.retrooper.packetevents.PacketEvents;
import gg.modl.minecraft.core.util.PublicMethods;
import gg.modl.minecraft.features.TeleportOutcome;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.staffmode.PacketSidebar;
import gg.modl.minecraft.features.staffmode.ScoreboardContent;
import gg.modl.minecraft.features.staffmode.StaffGameMode;
import gg.modl.minecraft.features.staffmode.StaffModeOps;
import gg.modl.minecraft.features.staffmode.StartingLocation;
import gg.modl.minecraft.spigot.features.folia.AsyncTeleporter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

class SpigotStaffModeOps implements StaffModeOps {
    private static final String SCOREBOARD_OBJECTIVE_NAME = "modl_staff";
    private static final Optional<Method> GET_PING =
            PublicMethods.withoutParameters(Player.class, "getPing").stream().findFirst();
    private static final int TARGET_INVENTORY_SIZE = 54;

    private final Logger logger;
    private final AsyncTeleporter teleporter;
    private final FeaturesLocaleManager localeManager;
    private final HotbarItems hotbarItems;
    private final PacketSidebar sidebar = new PacketSidebar(SCOREBOARD_OBJECTIVE_NAME);

    SpigotStaffModeOps(Logger logger, AsyncTeleporter teleporter, FeaturesLocaleManager localeManager) {
        this.logger = logger;
        this.teleporter = teleporter;
        this.localeManager = localeManager;
        this.hotbarItems = new HotbarItems(logger, PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());
    }

    @Override
    public boolean isOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.isOnline();
    }

    @Override
    public @Nullable String playerName(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : null;
    }

    @Override
    public Set<UUID> onlinePlayerUuids() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
    }

    @Override
    public int onlinePlayerCount() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int maxPlayerCount() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public int playerPing(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return -1;
        if (GET_PING.isPresent()) {
            try {
                return (int) GET_PING.get().invoke(player);
            } catch (ReflectiveOperationException e) {
                logger.log(Level.FINE, "getPing() invocation failed for " + player.getName(), e);
            }
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return handle.getClass().getField("ping").getInt(handle);
        } catch (ReflectiveOperationException e) {
            logger.log(Level.FINE, "NMS ping fallback failed for " + player.getName(), e);
            return -1;
        }
    }

    @Override
    public double playerHealth(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getHealth() : 0.0;
    }

    @Override
    public void clearInventory(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        player.getInventory().clear();
    }

    @Override
    public void setGameMode(UUID uuid, StaffGameMode mode) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        player.setGameMode(mode == StaffGameMode.CREATIVE ? GameMode.CREATIVE : GameMode.SURVIVAL);
    }

    @Override
    public void setHotbarSlot(UUID uuid, int slot, String materialId, String displayName, List<String> lore) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        ItemStack item = hotbarItems.create(materialId);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(slot, item);
    }

    @Override
    public Optional<StartingLocation> currentLocation(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return Optional.empty();
        Location location = player.getLocation();
        return Optional.of(new StartingLocation(location.getWorld().getUID(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
    }

    @Override
    public boolean canReturnTo(StartingLocation location) {
        return Bukkit.getWorld(location.getWorldId()) != null;
    }

    @Override
    public CompletableFuture<TeleportOutcome> teleportToStartingLocation(UUID uuid, StartingLocation location) {
        Player player = Bukkit.getPlayer(uuid);
        World world = Bukkit.getWorld(location.getWorldId());
        if (player == null || world == null) return CompletableFuture.completedFuture(TeleportOutcome.FAILED);
        return teleporter.attempt(player, new Location(world,
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
    }

    @Override
    public void reportRefusedReturn(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) teleporter.reportRefused(player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void hidePlayer(UUID viewerUuid, UUID hiddenUuid) {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        Player hidden = Bukkit.getPlayer(hiddenUuid);
        if (viewer != null && viewer.isOnline() && hidden != null && hidden.isOnline()) {
            viewer.hidePlayer(hidden);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void showPlayer(UUID viewerUuid, UUID shownUuid) {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        Player shown = Bukkit.getPlayer(shownUuid);
        if (viewer != null && viewer.isOnline() && shown != null && shown.isOnline()) {
            viewer.showPlayer(shown);
        }
    }

    @Override
    public void teleportToPlayer(UUID uuid, UUID targetUuid) {
        Player player = Bukkit.getPlayer(uuid);
        Player target = Bukkit.getPlayer(targetUuid);
        if (player != null && target != null) {
            teleporter.teleport(player, target.getLocation());
        }
    }

    @Override
    public void createScoreboard(UUID uuid, ScoreboardContent content) {
        sidebar.show(uuid, Bukkit.getPlayer(uuid), content);
    }

    @Override
    public void updateScoreboard(UUID uuid, ScoreboardContent content) {
        sidebar.update(uuid, Bukkit.getPlayer(uuid), content);
    }

    @Override
    public void removeScoreboard(UUID uuid) {
        sidebar.hide(uuid, Bukkit.getPlayer(uuid));
    }

    @Override
    public void discardScoreboard(UUID uuid) {
        sidebar.forget(uuid);
    }

    @Override
    public void clearScoreboards() {
        sidebar.forgetAll();
    }

    @Override
    public void openTargetInventory(UUID viewerUuid, UUID targetUuid) {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        Player target = Bukkit.getPlayer(targetUuid);
        if (viewer == null || target == null) return;
        Inventory copy = SilentContainer.create(TARGET_INVENTORY_SIZE,
                localeManager.getMessage("staff_mode.silent_container.inventory_title", mapOf("player", target.getName())));
        ItemStack[] contents = target.getInventory().getContents();
        for (int i = 0; i < contents.length && i < TARGET_INVENTORY_SIZE; i++) {
            copy.setItem(i, contents[i] == null ? null : contents[i].clone());
        }
        viewer.openInventory(copy);
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }
}
