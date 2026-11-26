package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;

final class TestPacketEvents extends PacketEventsAPI<Object> {
    private static volatile ServerVersion version = ServerVersion.V_1_21_11;
    private static final NettyManager NETTY = new NettyManagerImpl();

    private TestPacketEvents() {
    }

    static void install(ServerVersion serverVersion) {
        version = serverVersion;
        if (!(PacketEvents.getAPI() instanceof TestPacketEvents)) PacketEvents.setAPI(new TestPacketEvents());
    }

    @Override
    public void load() {
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public void init() {
    }

    @Override
    public boolean isInitialized() {
        return true;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public Object getPlugin() {
        return this;
    }

    @Override
    public ServerManager getServerManager() {
        return () -> version;
    }

    @Override
    public ProtocolManager getProtocolManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public PlayerManager getPlayerManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public NettyManager getNettyManager() {
        return NETTY;
    }

    @Override
    public ChannelInjector getInjector() {
        throw new UnsupportedOperationException();
    }
}
