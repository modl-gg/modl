package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class PacketTap extends PacketListenerAbstract {
    private final Map<UUID, Recording> recordings;
    private final PacketHandlers handlers;
    private final DecodeFailures failures;
    private final BiConsumer<UUID, @Nullable Object> disconnected;
    private final Function<User, @Nullable Recording> joining;

    PacketTap(Map<UUID, Recording> recordings, PacketHandlers handlers, DecodeFailures failures, BiConsumer<UUID, @Nullable Object> disconnected,
              Function<User, @Nullable Recording> joining) {
        super(PacketListenerPriority.MONITOR);
        this.recordings = recordings;
        this.handlers = handlers;
        this.failures = failures;
        this.disconnected = disconnected;
        this.joining = joining;
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        if (user == null) return;
        UUID uuid = user.getUUID();
        if (uuid != null && recordings.containsKey(uuid)) disconnected.accept(uuid, user.getChannel());
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) return;
        boolean join = event.getPacketType() == PacketType.Play.Server.JOIN_GAME;
        if (recordings.isEmpty() && !join) return;
        User user = event.getUser();
        if (user == null) return;
        Recording recording = join ? joining.apply(user) : recordingOf(user);
        if (recording == null || !recording.claimedBy(user.getChannel())) return;
        SendHandler handler = handlers.send(event.getPacketType());
        if (handler == null) return;
        recording.registries(user);
        try {
            ReadOnlyPacketEventScope.run(event, () -> handler.handle(event, recording, recording.now()));
        } catch (Exception e) {
            failures.record(String.valueOf(event.getPacketType()), e);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (recordings.isEmpty() || event.isCancelled()) return;
        User user = event.getUser();
        Recording recording = recordingOf(user);
        if (recording == null || user == null || !recording.claimedBy(user.getChannel())) return;
        ReceiveHandler handler = handlers.receive(event.getPacketType());
        if (handler == null) return;
        try {
            ReadOnlyPacketEventScope.run(event, () -> handler.handle(event, recording, recording.now()));
        } catch (Exception e) {
            failures.record(String.valueOf(event.getPacketType()), e);
        }
    }

    private @Nullable Recording recordingOf(@Nullable User user) {
        if (user == null) return null;
        UUID uuid = user.getUUID();
        return uuid == null ? null : recordings.get(uuid);
    }
}
