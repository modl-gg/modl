package dev.simplix.cirrus.common.packet;

import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;

final class TestPacketEventsApi extends PacketEventsAPI<Object> {

    private final NettyManager nettyManager = new NettyManagerImpl();
    private final ServerManager serverManager = () -> ServerVersion.V_1_21_4;

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
    public void terminate() {
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public Object getPlugin() {
        return null;
    }

    @Override
    public ServerManager getServerManager() {
        return serverManager;
    }

    @Override
    public ProtocolManager getProtocolManager() {
        return null;
    }

    @Override
    public PlayerManager getPlayerManager() {
        return null;
    }

    @Override
    public NettyManager getNettyManager() {
        return nettyManager;
    }

    @Override
    public ChannelInjector getInjector() {
        return null;
    }
}
