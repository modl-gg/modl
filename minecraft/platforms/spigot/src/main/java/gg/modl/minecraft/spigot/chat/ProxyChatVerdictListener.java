package gg.modl.minecraft.spigot.chat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import io.github._4drian3d.signedvelocity.shared.SignedConstants;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class ProxyChatVerdictListener implements Listener, AutoCloseable {
    private final ProxyChatVerdicts verdicts;
    private final PacketListenerAbstract packets;

    private ProxyChatVerdictListener(ProxyChatVerdicts verdicts) {
        this.verdicts = verdicts;
        this.packets = new DenialPackets(verdicts);
    }

    public static ProxyChatVerdictListener start(JavaPlugin plugin) {
        ProxyChatVerdictListener listener = new ProxyChatVerdictListener(new ProxyChatVerdicts());
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        PacketEvents.getAPI().getEventManager().registerListener(listener.packets);
        return listener;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!event.isAsynchronous()) return;
        if (verdicts.consumeDenial(event.getPlayer().getUniqueId(), ProxyVerdictChannel.CHAT, event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (verdicts.consumeDenial(event.getPlayer().getUniqueId(), ProxyVerdictChannel.COMMAND, event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        verdicts.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        PacketEvents.getAPI().getEventManager().unregisterListener(packets);
        verdicts.clear();
    }

    private static final class DenialPackets extends PacketListenerAbstract {
        private final ProxyChatVerdicts verdicts;

        private DenialPackets(ProxyChatVerdicts verdicts) {
            super(PacketListenerPriority.LOWEST);
            this.verdicts = verdicts;
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) return;
            UUID connectionPlayer = event.getUser().getUUID();
            if (connectionPlayer == null) return;
            WrapperPlayClientPluginMessage message = new WrapperPlayClientPluginMessage(event);
            if (!SignedConstants.SIGNED_PLUGIN_CHANNEL.equals(message.getChannelName())) return;
            ProxyVerdictMessage.denialIn(message.getData())
                    .ifPresent(denial -> verdicts.deny(connectionPlayer, denial.channel(), denial.deniedText()));
        }
    }
}
