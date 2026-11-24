package gg.modl.minecraft.spigot.chat;

import io.github._4drian3d.signedvelocity.shared.SignedConstants;
import io.github._4drian3d.signedvelocity.shared.types.QueueType;
import io.github._4drian3d.signedvelocity.shared.types.ResultType;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;

final class ProxyVerdictMessage {
    private final ProxyVerdictChannel channel;
    private final @Nullable String deniedText;

    private ProxyVerdictMessage(ProxyVerdictChannel channel, @Nullable String deniedText) {
        this.channel = channel;
        this.deniedText = deniedText;
    }

    static Optional<ProxyVerdictMessage> denialIn(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            input.readUTF();
            QueueType queue = QueueType.getOrThrow(input.readUTF());
            if (ResultType.getOrThrow(input.readUTF()) != ResultType.CANCEL) return Optional.empty();
            String text = input.available() > 0 ? SignedConstants.deniedTextExcerpt(input.readUTF()) : null;
            ProxyVerdictChannel channel = queue == QueueType.COMMAND ? ProxyVerdictChannel.COMMAND : ProxyVerdictChannel.CHAT;
            return Optional.of(new ProxyVerdictMessage(channel, text));
        } catch (IOException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    ProxyVerdictChannel channel() {
        return channel;
    }

    @Nullable String deniedText() {
        return deniedText;
    }
}
