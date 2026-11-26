package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.ChatType;
import com.github.retrooper.packetevents.protocol.chat.ChatTypeDecoration;
import com.github.retrooper.packetevents.protocol.chat.ChatTypes;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_16;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_19;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_19_1;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_19_3;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

final class ChatPackets {
    static final int SYSTEM = 1;
    static final int PLAYER = 2;
    static final int ACTION_BAR = 3;

    private static final UUID NO_SENDER = new UUID(0, 0);

    void register(PacketHandlers handlers) {
        handlers.onSend(PacketType.Play.Server.CHAT_MESSAGE, this::chat);
        handlers.onSend(PacketType.Play.Server.SYSTEM_CHAT_MESSAGE, this::system);
        handlers.onSend(PacketType.Play.Server.DISGUISED_CHAT, this::disguised);
        handlers.onSend(PacketType.Play.Server.ACTION_BAR, this::actionBar);
    }

    @SuppressWarnings("deprecation")
    private void chat(PacketSendEvent event, Recording recording, long time) {
        ChatMessage message = new WrapperPlayServerChatMessage(event).getMessage();
        UUID sender = message instanceof ChatMessage_v1_16 ? ((ChatMessage_v1_16) message).getSenderUUID() : null;
        if (message instanceof ChatMessage_v1_19_3) {
            ChatMessage_v1_19_3 modern = (ChatMessage_v1_19_3) message;
            Optional<Component> unsigned = modern.getUnsignedChatContent();
            write(recording, time, PLAYER, decorate(unsigned.isPresent() ? unsigned.get() : modern.getChatContent(), modern.getChatFormatting()), sender);
        } else if (message instanceof ChatMessage_v1_19_1) {
            ChatMessage_v1_19_1 signed = (ChatMessage_v1_19_1) message;
            Component unsigned = signed.getUnsignedChatContent();
            write(recording, time, PLAYER, decorate(unsigned != null ? unsigned : signed.getChatContent(), signed.getChatFormatting()), sender);
        } else if (message instanceof ChatMessage_v1_19) {
            ChatMessage_v1_19 early = (ChatMessage_v1_19) message;
            write(recording, time, PLAYER, Component.translatable("chat.type.text", early.getSenderDisplayName(), early.getChatContent()), sender);
        } else {
            boolean overlay = message.getType() == ChatTypes.GAME_INFO;
            boolean fromPlayer = sender != null && !NO_SENDER.equals(sender);
            int kind = overlay ? ACTION_BAR : (fromPlayer ? PLAYER : SYSTEM);
            write(recording, time, kind, message.getChatContent(), fromPlayer ? sender : null);
        }
    }

    private void system(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerSystemChatMessage packet = new WrapperPlayServerSystemChatMessage(event);
        write(recording, time, packet.isOverlay() ? ACTION_BAR : SYSTEM, packet.getMessage(), null);
    }

    private void disguised(PacketSendEvent event, Recording recording, long time) {
        WrapperPlayServerDisguisedChat packet = new WrapperPlayServerDisguisedChat(event);
        write(recording, time, PLAYER, decorate(packet.getMessage(), packet.getChatFormatting()), null);
    }

    private void actionBar(PacketSendEvent event, Recording recording, long time) {
        write(recording, time, ACTION_BAR, new WrapperPlayServerActionBar(event).getActionBarText(), null);
    }

    private static Component decorate(Component content, ChatType.@Nullable Bound bound) {
        if (bound == null) return content;
        ChatType type = bound.getType();
        ChatTypeDecoration decoration = type == null ? null : type.getChatDecoration();
        return decoration == null ? content : decoration.decorate(content, bound);
    }

    private static void write(Recording recording, long time, int kind, @Nullable Component component, @Nullable UUID sender) {
        if (component == null) return;
        String json = AdventureSerializer.serializer().asJson(component);
        if (json.isEmpty() || !RecordWriter.fits(json)) return;
        recording.append(RecordEncoder.chat(time, kind, json, sender), time);
    }
}
