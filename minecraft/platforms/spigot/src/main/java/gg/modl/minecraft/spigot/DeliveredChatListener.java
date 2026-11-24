package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.chat.ChatService;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class DeliveredChatListener implements Listener {
    private static final String MODERN_CHAT_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";

    private final ChatService chatService;
    private final Supplier<String> serverName;
    private final boolean modernEventRunsLast;
    private final Map<UUID, String> awaitingModernEvent = new ConcurrentHashMap<>();

    DeliveredChatListener(ChatService chatService, Supplier<String> serverName, boolean modernEventRunsLast) {
        this.chatService = chatService;
        this.serverName = serverName;
        this.modernEventRunsLast = modernEventRunsLast;
    }

    static DeliveredChatListener register(JavaPlugin plugin, ChatService chatService, Supplier<String> serverName) {
        Optional<Class<? extends Event>> modernEvent = modernChatEvent();
        DeliveredChatListener listener = new DeliveredChatListener(chatService, serverName, modernEvent.isPresent());
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(listener, plugin);
        modernEvent.ifPresent(type -> pluginManager.registerEvent(type, listener, EventPriority.MONITOR,
                (ignored, event) -> listener.onModernChat(event), plugin));
        return listener;
    }

    private static Optional<Class<? extends Event>> modernChatEvent() {
        try {
            return Optional.of(Class.forName(MODERN_CHAT_EVENT).asSubclass(Event.class));
        } catch (ClassNotFoundException serverWithoutModernChat) {
            return Optional.empty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!modernEventRunsLast) {
            record(player, event.getMessage(), event.isCancelled());
        } else if (event.isCancelled()) {
            awaitingModernEvent.remove(player.getUniqueId());
        } else {
            awaitingModernEvent.put(player.getUniqueId(), event.getMessage());
        }
    }

    void onModernChat(Event event) {
        if (!(event instanceof PlayerEvent) || !(event instanceof Cancellable)) return;
        Player player = ((PlayerEvent) event).getPlayer();
        String message = awaitingModernEvent.remove(player.getUniqueId());
        if (message != null) record(player, message, ((Cancellable) event).isCancelled());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingModernEvent.remove(event.getPlayer().getUniqueId());
    }

    private void record(Player player, String message, boolean cancelled) {
        chatService.recordChat(player.getUniqueId(), player.getName(), message, serverName.get(), cancelled);
    }
}
