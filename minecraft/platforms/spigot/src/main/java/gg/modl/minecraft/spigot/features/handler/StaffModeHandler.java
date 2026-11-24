package gg.modl.minecraft.spigot.features.handler;

import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.config.StaffModeConfig;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.staffmode.StaffModeCore;
import gg.modl.minecraft.features.staffmode.StaffModePersistence;
import gg.modl.minecraft.core.network.MemberActionSender;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.spigot.features.folia.AsyncTeleporter;
import gg.modl.minecraft.spigot.features.handler.belongings.SpigotBelongings;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class StaffModeHandler implements Listener {
    private final JavaPlugin plugin;
    private final FeaturesLocaleManager localeManager;
    private final StaffModeCore core;

    public StaffModeHandler(JavaPlugin plugin, FreezeHandler freezeHandler, FeaturesLocaleManager localeManager,
                            PluginLogger pluginLogger, StaffModeConfig staffModeConfig, FeatureScheduler scheduler,
                            MemberActionSender actions, Supplier<String> serverName) {
        this.plugin = plugin;
        this.localeManager = localeManager;
        this.core = new StaffModeCore(staffModeConfig, localeManager, pluginLogger, scheduler,
                freezeHandler.getFreezeCore(),
                new SpigotStaffModeOps(plugin.getLogger(), new AsyncTeleporter(pluginLogger), localeManager),
                new SpigotBelongings(plugin), StaffModePersistence.in(plugin.getDataFolder().toPath(), pluginLogger),
                actions, serverName);
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        core.start();
    }

    public void shutdown() {
        core.shutdown();
    }

    public StaffModeCore getCore() {
        return core;
    }

    public boolean isInStaffMode(UUID uuid) {
        return core.isInStaffMode(uuid);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getAction() == Action.PHYSICAL) {
            if (core.isVanished(uuid)) {
                event.setCancelled(true);
            }
            return;
        }

        if (!core.isInStaffMode(uuid)) {
            if (core.holdsBelongings(uuid)) event.setCancelled(true);
            return;
        }

        ItemStack item = event.getItem();
        if (item != null && item.hasItemMeta()) {
            event.setCancelled(true);
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                core.handleHotbarAction(uuid, player.getInventory().getHeldItemSlot());
            }
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && core.isVanished(uuid) && core.hasStaffInventory(uuid)) {
            Block block = event.getClickedBlock();
            if (block != null && block.getState() instanceof InventoryHolder) {
                InventoryHolder holder = (InventoryHolder) block.getState();
                event.setCancelled(true);
                Inventory copy = SilentContainer.create(holder.getInventory().getSize(),
                        localeManager.getMessage("staff_mode.silent_container.block_title",
                                mapOf("container", block.getType().name())));
                copy.setContents(holder.getInventory().getContents());
                player.openInventory(copy);
                return;
            }
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!core.isInStaffMode(uuid)) {
            if (core.holdsBelongings(uuid)) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        if (event.getRightClicked() instanceof Player) {
            Player clicked = (Player) event.getRightClicked();
            core.handleTargetSelect(uuid, player.getInventory().getHeldItemSlot(), clicked.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfRestricted(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (core.isInStaffMode(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamageOther(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player player = (Player) event.getDamager();
            if (core.isInStaffMode(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(PlayerPickupItemEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        if (core.holdsBelongings(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!core.holdsBelongings(event.getEntity().getUniqueId())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        core.handlePlayerRespawn(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (core.isInStaffMode(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!isRestricted(player)) return;

        if (SilentContainer.isSilent(event.getInventory())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (isRestricted(player) && !SilentContainer.isSilent(event.getInventory())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player) {
            Player player = (Player) event.getTarget();
            if (core.isVanished(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        core.handlePlayerJoin(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        core.handlePlayerQuit(event.getPlayer().getUniqueId());
    }

    private boolean isRestricted(Player player) {
        UUID uuid = player.getUniqueId();
        return core.isInStaffMode(uuid) || core.holdsBelongings(uuid);
    }

    private void cancelIfRestricted(Player player, Cancellable event) {
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }
}
