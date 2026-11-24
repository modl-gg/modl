package io.github._4drian3d.signedvelocity.paper;

import io.github._4drian3d.signedvelocity.shared.logger.DebugLogger;
import io.github._4drian3d.signedvelocity.common.queue.SignedQueue;
import io.github._4drian3d.signedvelocity.shared.SignedConstants;
import io.github._4drian3d.signedvelocity.paper.listener.EventListener;
import io.github._4drian3d.signedvelocity.paper.listener.PluginMessagingListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class SignedVelocity implements AutoCloseable {
    private final SignedQueue chatQueue = new SignedQueue();
    private final SignedQueue commandQueue = new SignedQueue();

    private SignedVelocity() {}

    public static SignedVelocity start(JavaPlugin plugin) {
        SignedVelocity signedVelocity = new SignedVelocity();
        try {
            signedVelocity.register(plugin, new DebugLogger.Slf4j(plugin.getSLF4JLogger()));
        } catch (RuntimeException | LinkageError failure) {
            signedVelocity.close();
            throw failure;
        }
        return signedVelocity;
    }

    private void register(JavaPlugin plugin, DebugLogger debugLogger) {
        plugin.getServer()
            .getMessenger()
            .registerIncomingPluginChannel(
                plugin,
                SignedConstants.SIGNED_PLUGIN_CHANNEL,
                new PluginMessagingListener(chatQueue, commandQueue, debugLogger)
            );

        EventListener.registerAll(plugin, chatQueue, commandQueue, debugLogger);
    }

    @Override
    public void close() {
        chatQueue.close();
        commandQueue.close();
    }
}
