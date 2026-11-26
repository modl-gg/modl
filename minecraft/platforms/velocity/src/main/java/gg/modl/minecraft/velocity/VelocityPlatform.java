package gg.modl.minecraft.velocity;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.velocity.wrapper.VelocityPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.network.ProxyBackend;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.command.ExecutableCommand;
import revxrsal.commands.velocity.VelocityLamp;
import revxrsal.commands.velocity.VelocityLampConfig;
import revxrsal.commands.velocity.VelocityVisitors;
import revxrsal.commands.velocity.actor.VelocityCommandActor;
import gg.modl.minecraft.core.StaffAudience;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.StringUtil;
import gg.modl.minecraft.core.integration.mojang.MojangProfiles;
import gg.modl.minecraft.core.integration.mojang.WebPlayer;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.velocitypowered.api.util.GameProfile;
import gg.modl.minecraft.core.util.PluginLogger;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

public class VelocityPlatform implements Platform {
    private final ProxyServer server;
    private final Object plugin;
    private final Logger logger;
    private final File dataFolder;
    private final String configServerName;
    private final boolean menusAvailable;
    private @Setter @Nullable StaffAudience staffAudience;

    private final PluginLogger pluginLogger;
    private final List<CommandMeta> registeredCommands = new CopyOnWriteArrayList<>();
    private volatile @Nullable VelocityLampConfig<VelocityCommandActor> lampConfig;

    public VelocityPlatform(Object plugin, ProxyServer server, Logger logger, File dataFolder,
                            String configServerName, PluginLogger pluginLogger, boolean menusAvailable) {
        this.server = server;
        this.plugin = plugin;
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.configServerName = configServerName;
        this.pluginLogger = pluginLogger;
        this.menusAvailable = menusAvailable;
    }

    public Component toKickComponent(String reason) {
        return Colors.get(StringUtil.unescapeNewlines(reason));
    }

    @Override
    public void broadcast(String string) {
        server.getAllPlayers().forEach(player -> player.sendMessage(Colors.get(string)));
    }

    @Override
    public void staffBroadcast(String string) {
        StaffAudience audience = staffAudience;
        if (audience != null) staffBroadcast(string, audience);
    }

    @Override
    public void staffBroadcast(String string, StaffAudience audience) {
        server.getAllPlayers().stream()
            .filter(player -> audience.includes(player.getUniqueId()))
            .forEach(player -> player.sendMessage(Colors.get(string)));
    }

    @Override
    public void connectToServer(UUID playerUuid, String serverName) {
        server.getPlayer(playerUuid).ifPresent(player ->
            server.getServer(serverName).ifPresent(srv ->
                player.createConnectionRequest(srv).fireAndForget()));
    }

    @Override
    public void staffJsonBroadcast(String jsonMessage) {
        StaffAudience audience = staffAudience;
        if (audience != null) staffJsonBroadcast(jsonMessage, audience);
    }

    @Override
    public void staffJsonBroadcast(String jsonMessage, StaffAudience audience) {
        server.getAllPlayers().stream()
            .filter(player -> audience.includes(player.getUniqueId()))
            .forEach(player -> sendJsonToPlayer(player, jsonMessage));
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        server.getPlayer(uuid).ifPresent(player -> player.sendMessage(Colors.get(StringUtil.unescapeNewlines(message))));
    }

    @Override
    public void sendJsonMessage(UUID uuid, String jsonMessage) {
        server.getPlayer(uuid).ifPresent(player -> sendJsonToPlayer(player, jsonMessage));
    }

    private void sendJsonToPlayer(Player player, String jsonMessage) {
        try {
            Component component = GsonComponentSerializer.gson().deserialize(jsonMessage);
            player.sendMessage(component);
        } catch (Exception e) {
            logger.warn("Failed to send JSON message to player", e);
            player.sendMessage(Component.text("Notification: " + jsonMessage));
        }
    }

    @Override
    public boolean isOnline(UUID uuid) {
        return server.getPlayer(uuid).isPresent();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Lamp<VelocityCommandActor> buildLamp(Consumer<Lamp.Builder<? extends CommandActor>> configurator) {
        VelocityLampConfig<VelocityCommandActor> config = VelocityLampConfig.<VelocityCommandActor>builder(plugin, server)
                .actorFactory(SectionCodeVelocityActor::new)
                .build();
        lampConfig = config;
        Lamp.Builder<VelocityCommandActor> builder = VelocityLamp.builder(config)
                .defaultMessageSender(VelocityCommandActor::sendRawMessage)
                .defaultErrorSender(VelocityCommandActor::sendRawError);
        configurator.accept((Lamp.Builder) builder);
        return builder.build();
    }

    @Override
    public void describeCommandErrors(CommandErrorCatalog catalog) {
        VelocityCommandErrors.addTo(catalog);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommandActor networkConsoleActor(Lamp<? extends CommandActor> lamp) {
        return new VelocityNetworkConsoleActor(server.getConsoleCommandSource(), (Lamp<VelocityCommandActor>) lamp);
    }

    @Override
    public boolean hasConsoleSender(CommandActor actor) {
        return actor instanceof VelocityCommandActor velocityActor
                && velocityActor.source() instanceof ConsoleCommandSource;
    }

    @Override
    public boolean hostsNetworkMembers() {
        return true;
    }

    @Override
    public List<ProxyBackend> proxyBackends() {
        List<ProxyBackend> backends = new ArrayList<>();
        server.getAllServers().forEach(registered -> backends.add(
                ProxyBackend.of(registered.getServerInfo().getName(), registered.getServerInfo().getAddress())));
        return backends;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void finalizeLampRegistration(Lamp<? extends CommandActor> lamp) {
        Lamp<VelocityCommandActor> velocityLamp = (Lamp<VelocityCommandActor>) lamp;
        VelocityLampConfig<VelocityCommandActor> config = lampConfig;
        if (config == null) throw new IllegalStateException("finalizeLampRegistration ran before buildLamp");
        velocityLamp.accept(VelocityVisitors.brigadier(config));
        for (String label : rootLabels(lamp)) {
            CommandMeta registered = server.getCommandManager().getCommandMeta(label);
            if (registered != null) registeredCommands.add(registered);
        }
    }

    @Override
    public void unregisterCommands(Lamp<? extends CommandActor> lamp) {
        registeredCommands.forEach(server.getCommandManager()::unregister);
        registeredCommands.clear();
        lamp.unregisterAllCommands();
    }

    private static Set<String> rootLabels(Lamp<? extends CommandActor> lamp) {
        Set<String> labels = new LinkedHashSet<>();
        for (ExecutableCommand<?> command : lamp.registry().commands()) labels.add(command.firstNode().name());
        return labels;
    }

    private @Nullable Player getOnlinePlayer(String username) {
        return server.getPlayer(username).orElse(null);
    }

    private @Nullable Player getOnlinePlayer(UUID uuid) {
        return server.getPlayer(uuid).orElse(null);
    }

    @Override
    public @Nullable AbstractPlayer getAbstractPlayer(UUID uuid, boolean queryMojang) {
        Player player = getOnlinePlayer(uuid);
        if (player != null) return toOnlineAbstractPlayer(player);
        return resolveOfflineAbstractPlayer(queryMojang, () -> MojangProfiles.client().getSync(uuid));
    }

    @Override
    public @Nullable AbstractPlayer getAbstractPlayer(String username, boolean queryMojang) {
        Player player = getOnlinePlayer(username);
        if (player != null) return toOnlineAbstractPlayer(player);
        return resolveOfflineAbstractPlayer(queryMojang, () -> MojangProfiles.client().getSync(username));
    }

    private AbstractPlayer toOnlineAbstractPlayer(Player player) {
        return new AbstractPlayer(player.getUniqueId(), player.getUsername(),
                player.getRemoteAddress().getAddress().getHostAddress(), true);
    }

    private @Nullable AbstractPlayer resolveOfflineAbstractPlayer(boolean queryMojang,
                                                                  Supplier<@Nullable WebPlayer> lookup) {
        if (!queryMojang) return null;

        @Nullable WebPlayer webPlayer;
        try {
            webPlayer = lookup.get();
        } catch (Exception ignored) {
            return null;
        }

        if (webPlayer == null) return null;
        UUID uuid = webPlayer.getUuid();
        String name = webPlayer.getName();
        if (uuid == null || name == null) return null;
        return new AbstractPlayer(uuid, name, false);
    }

    @Override
    public boolean areMenusAvailable() {
        return menusAvailable;
    }

    @Override
    public @Nullable CirrusPlayerWrapper getPlayerWrapper(UUID uuid) {
        if (!menusAvailable) return null;
        Player player = getOnlinePlayer(uuid);
        return player != null ? new VelocityPlayerWrapper(player) : null;
    }

    @Override
    public Collection<AbstractPlayer> getOnlinePlayers() {
        return server.getAllPlayers().stream()
                .map(player -> new AbstractPlayer(
                        player.getUniqueId(),
                        player.getUsername(),
                        player.getRemoteAddress().getAddress().getHostAddress(),
                        true
                ))
                .collect(Collectors.toList());
    }

    @Override
    public @Nullable AbstractPlayer getPlayer(UUID uuid) {
        Player player = server.getPlayer(uuid).orElse(null);
        if (player == null) return null;

        return new AbstractPlayer(
                player.getUniqueId(),
                player.getUsername(),
                player.getRemoteAddress().getAddress().getHostAddress(),
                true
        );
    }

    @Override
    public int getMaxPlayers() {
        return server.getConfiguration().getShowMaxPlayers();
    }

    @Override
    public String getServerVersion() {
        return server.getVersion().getVersion();
    }

    @Override
    public String getPlatformType() {
        return "velocity";
    }

    @Override
    public void runOnMainThread(Runnable task) {
        task.run();
    }

    @Override
    public void kickPlayer(AbstractPlayer player, String reason) {
        if (player == null) return;
        server.getPlayer(player.getUuid()).ifPresent(velocityPlayer -> velocityPlayer.disconnect(toKickComponent(reason)));
    }

    @Override
    public String getServerName() {
        return configServerName;
    }

    @Override
    public String getPlayerServer(UUID uuid) {
        return server.getPlayer(uuid)
            .flatMap(Player::getCurrentServer)
            .map(conn -> conn.getServerInfo().getName())
            .orElse(getServerName());
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public boolean isPluginEnabled(String name) {
        return server.getPluginManager().getPlugin(name.toLowerCase(Locale.ROOT)).isPresent();
    }

    @Override
    public Collection<String> commandNames(String label) {
        CommandMeta meta = server.getCommandManager().getCommandMeta(label);
        if (meta == null) return Collections.singleton(label);
        Set<String> names = new LinkedHashSet<>(meta.getAliases());
        names.add(label);
        return Collections.unmodifiableSet(names);
    }

    @Override
    public void dispatchPlayerCommand(UUID uuid, String command) {
        server.getPlayer(uuid).ifPresent(player ->
                Futures.ignoreResult(server.getCommandManager().executeAsync(player, command), pluginLogger));
    }

    @Override
    public void dispatchConsoleCommand(String command) {
        Futures.ignoreResult(server.getCommandManager().executeAsync(server.getConsoleCommandSource(), command), pluginLogger);
    }

    @Override
    public @Nullable String getPlayerSkinTexture(UUID uuid) {
        Player player = getOnlinePlayer(uuid);
        return player == null ? null : texturesOf(player);
    }

    static @Nullable String texturesOf(Player player) {
        for (GameProfile.Property prop : player.getGameProfileProperties()) {
            if ("textures".equals(prop.getName())) return prop.getValue();
        }
        return null;
    }

    @Override
    public boolean assignsOfflineUuids() {
        return !server.getConfiguration().isOnlineMode();
    }

    @Override
    public boolean isOnlineModeAuthenticated(UUID uuid) {
        return server.getConfiguration().isOnlineMode()
                && server.getPlayer(uuid).map(Player::isOnlineMode).orElse(false);
    }

    @Override
    public PluginLogger getLogger() {
        return pluginLogger;
    }

    @Override
    public void log(String msg) {
        logger.info(msg);
    }

}
