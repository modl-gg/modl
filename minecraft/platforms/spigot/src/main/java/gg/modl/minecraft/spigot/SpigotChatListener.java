package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.chat.ChatService;
import gg.modl.minecraft.core.chat.CommandInterceptService;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.function.Supplier;

@RequiredArgsConstructor
final class SpigotChatListener implements Listener {
    private final ChatService chatService;
    private final CommandInterceptService commandInterceptService;
    private final ChatCommandLogService chatCommandLogService;
    private final Supplier<String> serverName;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatService.Result result = chatService.handleChat(player.getUniqueId(), player.getName(), event.getMessage(),
                serverName.get(), event.isCancelled(), player::sendMessage);
        if (result == ChatService.Result.CANCELLED) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        CommandInterceptService.CommandResult result = commandInterceptService.check(player.getUniqueId(), event.getMessage());
        if (result == CommandInterceptService.CommandResult.ALLOWED) return;
        event.setCancelled(true);
        player.sendMessage(commandInterceptService.getBlockMessage(result, player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandRan(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        chatCommandLogService.addCommand(player.getUniqueId().toString(), player.getName(), event.getMessage(),
                serverName.get());
    }
}
