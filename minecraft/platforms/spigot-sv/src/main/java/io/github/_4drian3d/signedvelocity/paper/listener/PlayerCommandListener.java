package io.github._4drian3d.signedvelocity.paper.listener;

import io.github._4drian3d.signedvelocity.shared.logger.DebugLogger;
import io.github._4drian3d.signedvelocity.common.queue.SignedQueue;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;

public final class PlayerCommandListener implements EventListener<PlayerCommandPreprocessEvent>, LocalExecutionDetector {
    private static final Class<?> CHAT_COMMAND_PACKET;
    private static final Class<?> CHAT_COMMAND_SIGNED_PACKET;

    static {
        Class<?> chatCmd = null;
        Class<?> chatCmdSigned = null;
        try {
            chatCmd = Class.forName("net.minecraft.network.protocol.game.ServerboundChatCommandPacket");
        } catch (ClassNotFoundException ignored) {}
        try {
            chatCmdSigned = Class.forName("net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket");
        } catch (ClassNotFoundException ignored) {}
        CHAT_COMMAND_PACKET = chatCmd;
        CHAT_COMMAND_SIGNED_PACKET = chatCmdSigned;
    }

    private final SignedQueue commandQueue;
    private final DebugLogger debugLogger;

    public PlayerCommandListener(final SignedQueue commandQueue, final DebugLogger debugLogger) {
        this.commandQueue = commandQueue;
        this.debugLogger = debugLogger;
    }

    @Override
    public EventPriority priority() {
        return EventPriority.LOWEST;
    }

    @Override
    public boolean ignoreCancelled() {
        return false;
    }

    @Override
    public void handle(final PlayerCommandPreprocessEvent event) {
        if (CHECK_FOR_LOCAL_CHAT && isLocal()) {
            debugLogger.debug(() -> "[COMMAND] Local Command Executed");
            return;
        }

        final Player player = event.getPlayer();
        this.commandQueue.dataFrom(player.getUniqueId())
                .acceptNextResult(result -> {
                    if (result.cancelled()) {
                        debugLogger.debug(() -> "[COMMAND] Canceled Command");
                        event.setCancelled(true);
                    } else {
                        final String modified = result.toModify();
                        if (modified != null) {
                            debugLogger.debug(() -> "[COMMAND] Modified Command");
                            event.setMessage(modified);
                        }
                    }
                });
    }

    @Override
    public Class<PlayerCommandPreprocessEvent> eventClass() {
        return PlayerCommandPreprocessEvent.class;
    }

    @Override
    public boolean isLocal() {
        return WALKER.walk(stream -> stream.limit(15)
                .map(StackWalker.StackFrame::getMethodType)
                .noneMatch(method -> {
                    List<Class<?>> parameters = method.parameterList();
                    return (CHAT_COMMAND_PACKET != null && parameters.contains(CHAT_COMMAND_PACKET))
                            || (CHAT_COMMAND_SIGNED_PACKET != null && parameters.contains(CHAT_COMMAND_SIGNED_PACKET));
                }));
    }
}
