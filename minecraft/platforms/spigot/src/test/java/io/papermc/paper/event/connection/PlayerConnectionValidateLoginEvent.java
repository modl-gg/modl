package io.papermc.paper.event.connection;

import io.papermc.paper.connection.PlayerConnection;
import net.kyori.adventure.text.Component;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

public class PlayerConnectionValidateLoginEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final PlayerConnection connection;
    private @Nullable Component kickMessage;

    public PlayerConnectionValidateLoginEvent(PlayerConnection connection, @Nullable Component kickMessage) {
        this.connection = connection;
        this.kickMessage = kickMessage;
    }

    public PlayerConnection getConnection() {
        return connection;
    }

    public void allow() {
        kickMessage = null;
    }

    public void kickMessage(Component message) {
        kickMessage = message;
    }

    public @Nullable Component getKickMessage() {
        return kickMessage;
    }

    public boolean isAllowed() {
        return kickMessage == null;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
