package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.chat.ChatService;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEvent;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveredChatListenerTest {
    private static final String SERVER = "survival";

    private final ChatService chatService = mock(ChatService.class);
    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);

    DeliveredChatListenerTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Player");
    }

    @Test
    void withoutTheModernEventTheLegacyResultDecides() {
        DeliveredChatListener listener = new DeliveredChatListener(chatService, () -> SERVER, false);

        listener.onLegacyChat(legacy("/login secret", true));

        verify(chatService).recordChat(uuid, "Player", "/login secret", SERVER, true);
    }

    @Test
    void aMessageCancelledByTheModernEventIsRecordedAsCancelled() {
        DeliveredChatListener listener = new DeliveredChatListener(chatService, () -> SERVER, true);

        listener.onLegacyChat(legacy("hello", false));
        listener.onModernChat(new ModernChat(player, true));

        verify(chatService).recordChat(uuid, "Player", "hello", SERVER, true);
    }

    @Test
    void aMessageCancelledByTheLegacyEventIsNeverRecorded() {
        DeliveredChatListener listener = new DeliveredChatListener(chatService, () -> SERVER, true);

        listener.onLegacyChat(legacy("secret", true));
        listener.onModernChat(new ModernChat(player, false));

        verify(chatService, never()).recordChat(eq(uuid), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aDeliveredMessageIsRecordedOnce() {
        DeliveredChatListener listener = new DeliveredChatListener(chatService, () -> SERVER, true);

        listener.onLegacyChat(legacy("hello", false));
        listener.onModernChat(new ModernChat(player, false));
        listener.onModernChat(new ModernChat(player, false));

        verify(chatService).recordChat(uuid, "Player", "hello", SERVER, false);
    }

    private AsyncPlayerChatEvent legacy(String message, boolean cancelled) {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, message, Collections.<Player>emptySet());
        event.setCancelled(cancelled);
        return event;
    }

    private static final class ModernChat extends PlayerEvent implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;

        private ModernChat(Player player, boolean cancelled) {
            super(player);
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            cancelled = cancel;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }
}
