package gg.modl.minecraft.spigot.features.handler;

import gg.modl.minecraft.features.FeatureScheduler;
import gg.modl.minecraft.features.locale.FeaturesLocaleManager;
import gg.modl.minecraft.features.freeze.FrozenPlayers;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class MemberFreezeChatGuard implements Listener {
    private final JavaPlugin plugin;
    private final FeaturesLocaleManager localeManager;
    private final FeatureScheduler scheduler;
    private final FrozenPlayers frozenPlayers;
    private final StaffModeHandler staffModeHandler;

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!frozenPlayers.isFrozen(player.getUniqueId())) return;

        event.setCancelled(true);

        UUID playerUuid = player.getUniqueId();
        String message = localeManager.getMessage("freeze.chat",
                mapOf("player", player.getName(), "message", event.getMessage()));

        scheduler.runOnMainThread(() -> {
            Bukkit.getOnlinePlayers().stream()
                    .filter(online -> staffModeHandler.isInStaffMode(online.getUniqueId()))
                    .forEach(online -> sendScheduledMessage(online.getUniqueId(), message));

            sendScheduledMessage(playerUuid, message);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!frozenPlayers.isFrozen(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(localeManager.getMessage("freeze.no_commands"));
    }

    private void sendScheduledMessage(UUID target, String message) {
        scheduler.runForPlayer(target, () -> {
            Player player = Bukkit.getPlayer(target);
            if (player != null) {
                player.sendMessage(message);
            }
        });
    }
}
