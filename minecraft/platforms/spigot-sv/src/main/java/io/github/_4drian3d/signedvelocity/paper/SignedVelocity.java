package io.github._4drian3d.signedvelocity.paper;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import io.github._4drian3d.signedvelocity.shared.logger.DebugLogger;
import io.github._4drian3d.signedvelocity.common.queue.SignedQueue;
import io.github._4drian3d.signedvelocity.paper.listener.EventListener;
import io.github._4drian3d.signedvelocity.paper.listener.PluginMessagingListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class SignedVelocity implements AutoCloseable {
    private final SignedQueue chatQueue = new SignedQueue();
    private final SignedQueue commandQueue = new SignedQueue();
    private @Nullable PacketListenerCommon verdicts;

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
        verdicts = PacketEvents.getAPI().getEventManager()
            .registerListener(new PluginMessagingListener(chatQueue, commandQueue, debugLogger));
        EventListener.registerAll(plugin, chatQueue, commandQueue, debugLogger);
    }

    @Override
    public void close() {
        PacketListenerCommon registered = verdicts;
        verdicts = null;
        if (registered != null) PacketEvents.getAPI().getEventManager().unregisterListener(registered);
        chatQueue.close();
        commandQueue.close();
    }
}
