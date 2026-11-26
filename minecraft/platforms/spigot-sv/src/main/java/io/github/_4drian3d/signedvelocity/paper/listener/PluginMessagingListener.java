package io.github._4drian3d.signedvelocity.paper.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import io.github._4drian3d.signedvelocity.common.queue.SignedQueue;
import io.github._4drian3d.signedvelocity.common.queue.SignedResult;
import io.github._4drian3d.signedvelocity.shared.SignedConstants;
import io.github._4drian3d.signedvelocity.shared.logger.DebugLogger;
import io.github._4drian3d.signedvelocity.shared.types.QueueType;
import io.github._4drian3d.signedvelocity.shared.types.ResultType;

import java.util.UUID;

public final class PluginMessagingListener extends PacketListenerAbstract {
    private final SignedQueue chatQueue;
    private final SignedQueue commandQueue;
    private final DebugLogger debugLogger;

    public PluginMessagingListener(final SignedQueue chatQueue, final SignedQueue commandQueue,
                                   final DebugLogger debugLogger) {
        super(PacketListenerPriority.LOWEST);
        this.chatQueue = chatQueue;
        this.commandQueue = commandQueue;
        this.debugLogger = debugLogger;
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) {
            return;
        }
        final UUID sender = event.getUser().getUUID();
        if (sender == null) {
            return;
        }
        final WrapperPlayClientPluginMessage message = new WrapperPlayClientPluginMessage(event);
        if (SignedConstants.SIGNED_PLUGIN_CHANNEL.equals(message.getChannelName())) {
            accept(sender, message.getData());
        }
    }

    void accept(final UUID sender, final byte[] message) {
        debugLogger.debug(() -> "[Plugin Message] Received on: " + System.currentTimeMillis());
        try {
            final ByteArrayDataInput input = ByteStreams.newDataInput(message);

            final UUID playerId = UUID.fromString(input.readUTF());
            final String source = input.readUTF();
            final String result = input.readUTF();

            final SignedQueue queue = QueueType.getOrThrow(source) == QueueType.COMMAND ? commandQueue : chatQueue;

            final ResultType resultType = ResultType.getOrThrow(result);
            final SignedResult resulted;
            if (resultType == ResultType.CANCEL) {
                resulted = SignedResult.cancel();
            } else if (resultType == ResultType.MODIFY) {
                resulted = SignedResult.modify(input.readUTF());
            } else {
                resulted = SignedResult.allowed();
            }

            if (!playerId.equals(sender)) {
                debugLogger.debug(() -> "[Plugin Message] Dropping payload: embedded UUID " + playerId + " != sender " + sender);
                return;
            }

            queue.dataFrom(playerId).complete(resulted);
            debugLogger.debugMultiple(() -> new String[]{
                "[Plugin Message] Received Valid Message",
                "| Queue: " + source,
                "| Result: " + result
            });
        } catch (final IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException exception) {
            debugLogger.debug(() -> "[Plugin Message] Dropping malformed payload: " + exception.getMessage());
        }
    }
}
