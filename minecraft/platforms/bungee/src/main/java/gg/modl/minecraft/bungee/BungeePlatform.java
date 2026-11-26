package gg.modl.minecraft.bungee;

import dev.simplix.cirrus.bungee.wrapper.BungeePlayerWrapper;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.network.ProxyBackend;
import net.md_5.bungee.api.plugin.Plugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.bungee.BungeeLamp;
import revxrsal.commands.bungee.actor.BungeeCommandActor;
import gg.modl.minecraft.core.StaffAudience;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import gg.modl.minecraft.core.util.StringUtil;
import lombok.Setter;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import java.util.function.Consumer;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import gg.modl.minecraft.core.util.PluginLogger;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.chat.ComponentSerializer;
import org.jspecify.annotations.Nullable;

public class BungeePlatform implements Platform {
    private final Plugin plugin;
    private final Logger logger;
    private final File dataFolder;
    private final String configServerName;
    private final boolean menusAvailable;
    private final PluginLogger pluginLogger;
    private final BungeeSkinResolver skinResolver;
    private @Setter @Nullable StaffAudience staffAudience;

    public BungeePlatform(Plugin plugin, Logger logger, File dataFolder, String configServerName,
                          boolean menusAvailable) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.configServerName = configServerName;
        this.menusAvailable = menusAvailable;
        this.pluginLogger = PluginLogger.fromJul(logger);
        this.skinResolver = new BungeeSkinResolver(pluginLogger);
    }

    @Override
    public void broadcast(String string) {
        TextComponent message = Colors.get(string);
        ProxyServer.getInstance().broadcast(message);
    }

    @Override
    public void staffBroadcast(String string) {
        StaffAudience audience = staffAudience;
        if (audience != null) staffBroadcast(string, audience);
    }

    @Override
    public void staffBroadcast(String string, StaffAudience audience) {
        TextComponent message = Colors.get(string);
        ProxyServer.getInstance().getPlayers().stream()
            .filter(player -> audience.includes(player.getUniqueId()))
            .forEach(player -> player.sendMessage(message));
    }

    @Override
    public void connectToServer(UUID player, String serverName) {
        ProxiedPlayer pp = ProxyServer.getInstance().getPlayer(player);
        if (pp != null) {
            ServerInfo server = ProxyServer.getInstance().getServerInfo(serverName);
            if (server != null) pp.connect(server);
        }
    }

    @Override
    public void staffJsonBroadcast(String jsonMessage) {
        StaffAudience audience = staffAudience;
        if (audience != null) staffJsonBroadcast(jsonMessage, audience);
    }

    @Override
    public void staffJsonBroadcast(String jsonMessage, StaffAudience audience) {
        ProxyServer.getInstance().getPlayers().stream()
            .filter(player -> audience.includes(player.getUniqueId()))
            .forEach(player -> player.sendMessage(ComponentSerializer.parse(jsonMessage)));
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player != null && player.isConnected()) player.sendMessage(Colors.get(StringUtil.unescapeNewlines(message)));
    }

    @Override
    public void sendJsonMessage(UUID uuid, String jsonMessage) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player != null && player.isConnected()) player.sendMessage(ComponentSerializer.parse(jsonMessage));
    }

    @Override
    public boolean isOnline(UUID uuid) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        return player != null && player.isConnected();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Lamp<BungeeCommandActor> buildLamp(Consumer<Lamp.Builder<? extends CommandActor>> configurator) {
        Lamp.Builder<BungeeCommandActor> builder = BungeeLamp.builder(plugin, new ConsoleAwareBungeeActor.Factory());
        configurator.accept((Lamp.Builder) builder);
        return builder.build();
    }

    @Override
    public void describeCommandErrors(CommandErrorCatalog catalog) {
        BungeeCommandErrors.addTo(catalog);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommandActor networkConsoleActor(Lamp<? extends CommandActor> lamp) {
        return new BungeeNetworkConsoleActor(ProxyServer.getInstance().getConsole(), (Lamp<BungeeCommandActor>) lamp);
    }

    @Override
    public boolean hasConsoleSender(CommandActor actor) {
        return actor instanceof BungeeCommandActor
                && ((BungeeCommandActor) actor).sender() == ProxyServer.getInstance().getConsole();
    }

    @Override
    public boolean hostsNetworkMembers() {
        return true;
    }

    @Override
    public List<ProxyBackend> proxyBackends() {
        List<ProxyBackend> backends = new ArrayList<>();
        for (ServerInfo info : new ArrayList<>(ProxyServer.getInstance().getServers().values())) {
            backends.add(ProxyBackend.of(info.getName(), info.getSocketAddress()));
        }
        return backends;
    }

    @Override
    public @Nullable AbstractPlayer getAbstractPlayer(UUID uuid, boolean queryMojang) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        return player != null ? toAbstractPlayer(player) : null;
    }

    @Override
    public @Nullable AbstractPlayer getAbstractPlayer(String username, boolean queryMojang) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(username);
        return player != null ? toAbstractPlayer(player) : null;
    }

    @Override
    public boolean areMenusAvailable() {
        return menusAvailable;
    }

    public TextComponent toKickComponent(String reason) {
        return Colors.get(StringUtil.unescapeNewlines(reason));
    }

    @Override
    public @Nullable CirrusPlayerWrapper getPlayerWrapper(UUID uuid) {
        if (!menusAvailable) return null;
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        return player != null ? new BungeePlayerWrapper(player) : null;
    }

    @Override
    public Collection<AbstractPlayer> getOnlinePlayers() {
        return ProxyServer.getInstance().getPlayers().stream()
            .map(this::toAbstractPlayer)
            .collect(Collectors.toList());
    }

    @Override
    public @Nullable AbstractPlayer getPlayer(UUID uuid) {
        return getAbstractPlayer(uuid, false);
    }

    @Override
    public int getMaxPlayers() {
        return ProxyServer.getInstance().getConfig().getPlayerLimit();
    }

    @Override
    public String getServerVersion() {
        return ProxyServer.getInstance().getVersion();
    }

    @Override
    public String getPlatformType() {
        return "bungee";
    }

    @Override
    public void runOnMainThread(Runnable task) {
        task.run();
    }

    @Override
    public void kickPlayer(AbstractPlayer player, String reason) {
        if (player == null) return;
        ProxiedPlayer bungeePlayer = ProxyServer.getInstance().getPlayer(player.getUuid());
        if (bungeePlayer != null && bungeePlayer.isConnected()) bungeePlayer.disconnect(toKickComponent(reason));
    }

    @Override
    public String getServerName() {
        return configServerName;
    }

    @Override
    public String getPlayerServer(UUID uuid) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player != null && player.getServer() != null) return player.getServer().getInfo().getName();
        return getServerName();
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public boolean assignsOfflineUuids() {
        return !ProxyServer.getInstance().getConfig().isOnlineMode();
    }

    @Override
    public boolean isOnlineModeAuthenticated(UUID uuid) {
        ProxyServer proxy = ProxyServer.getInstance();
        ProxiedPlayer player = proxy.getPlayer(uuid);
        return proxy.getConfig().isOnlineMode() && player != null && player.getPendingConnection().isOnlineMode();
    }

    @Override
    public boolean isPluginEnabled(String name) {
        return ProxyServer.getInstance().getPluginManager().getPlugin(name) != null;
    }

    @Override
    public PluginLogger getLogger() {
        return pluginLogger;
    }

    private AbstractPlayer toAbstractPlayer(ProxiedPlayer player) {
        String ip = (player.getSocketAddress() instanceof InetSocketAddress)
                ? ((InetSocketAddress) player.getSocketAddress()).getAddress().getHostAddress() : null;
        return new AbstractPlayer(player.getUniqueId(), player.getName(), ip, player.isConnected());
    }

    @Override
    public Collection<String> commandNames(String label) {
        for (Map.Entry<String, Command> registered : ProxyServer.getInstance().getPluginManager().getCommands()) {
            if (!registered.getKey().equalsIgnoreCase(label)) continue;
            Command command = registered.getValue();
            Set<String> names = new LinkedHashSet<>(Arrays.asList(command.getAliases()));
            names.add(command.getName());
            names.add(label);
            return Collections.unmodifiableSet(names);
        }
        return Collections.singleton(label);
    }

    @Override
    public void dispatchPlayerCommand(UUID uuid, String command) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player != null) ProxyServer.getInstance().getPluginManager().dispatchCommand(player, command);
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        ProxyServer.getInstance().getPluginManager().dispatchCommand(
                ProxyServer.getInstance().getConsole(), command);
    }

    @Override
    public @Nullable String getPlayerSkinTexture(UUID uuid) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
        if (player == null) return null;
        return skinResolver.resolveTexture(player);
    }

    @Nullable String loginTexture(PendingConnection connection) {
        return skinResolver.resolveTexture(connection);
    }

    @Override
    public void log(String msg) {
        logger.info(msg);
    }

}
