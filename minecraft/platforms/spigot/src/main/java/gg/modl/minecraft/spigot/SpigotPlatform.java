package gg.modl.minecraft.spigot;

import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import dev.simplix.cirrus.spigot.wrapper.SpigotPlayerWrapper;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.StaffAudience;
import gg.modl.minecraft.core.command.error.CommandErrorCatalog;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.Lamp;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.BukkitLampConfig;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import gg.modl.minecraft.core.util.StringUtil;
import gg.modl.minecraft.spigot.features.folia.FoliaScheduler;
import lombok.Setter;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.Consumer;
import java.io.File;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import gg.modl.minecraft.core.util.PluginLogger;

public class SpigotPlatform implements Platform {
    private static final int NAME_DERIVED_UUID_VERSION = 3;
    private final Logger logger;
    private final File dataFolder;
    private final String configServerName;
    private final JavaPlugin plugin;
    private final ProxyForwarding proxyForwarding;
    private final PluginLogger pluginLogger;
    private final FoliaScheduler foliaScheduler;
    private @Setter @Nullable StaffAudience staffAudience;

    private final SkinTextureSource skinTextures;
    private final SkinTextureSource preLoginTextures;
    private final SpigotCommandNames commandNames;

    public SpigotPlatform(JavaPlugin plugin, Logger logger, File dataFolder, String configServerName,
                          ProxyForwarding proxyForwarding) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.configServerName = configServerName;
        this.proxyForwarding = proxyForwarding;
        this.pluginLogger = PluginLogger.fromJul(logger);
        this.foliaScheduler = new FoliaScheduler(plugin);
        this.skinTextures = NativeSkinTextures.detect(Player.class, pluginLogger);
        this.preLoginTextures = NativeSkinTextures.detect(AsyncPlayerPreLoginEvent.class, pluginLogger);
        this.commandNames = SpigotCommandNames.detect(plugin.getServer(), pluginLogger);
    }

    @Override
    public void broadcast(String string) {
        runOnMainThread(() -> Bukkit.broadcastMessage(Colors.get(string)));
    }

    @Override
    public void staffBroadcast(String string) {
        StaffAudience audience = staffAudience;
        if (audience != null) staffBroadcast(string, audience);
    }

    @Override
    public void staffBroadcast(String string, StaffAudience audience) {
        String message = Colors.get(string);
        runOnMainThread(() -> Bukkit.getOnlinePlayers().stream()
            .filter(player -> audience.includes(player.getUniqueId()))
            .forEach(player -> player.sendMessage(message)));
    }

    @Override
    public void staffJsonBroadcast(String jsonMessage) {
        StaffAudience audience = staffAudience;
        if (audience != null) staffJsonBroadcast(jsonMessage, audience);
    }

    @Override
    public void staffJsonBroadcast(String jsonMessage, StaffAudience audience) {
        runOnMainThread(() -> Bukkit.getOnlinePlayers().stream()
            .filter(player -> audience.includes(player.getUniqueId()))
            .forEach(player -> player.spigot().sendMessage(ComponentSerializer.parse(jsonMessage))));
    }

    @Override
    public void sendMessage(UUID uuid, String message) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) player.sendMessage(Colors.get(StringUtil.unescapeNewlines(message)));
    }

    @Override
    public void sendJsonMessage(UUID uuid, String jsonMessage) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) player.spigot().sendMessage(ComponentSerializer.parse(jsonMessage));
    }

    @Override
    public boolean isOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.isOnline();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Lamp<BukkitCommandActor> buildLamp(Consumer<Lamp.Builder<? extends CommandActor>> configurator) {
        Lamp.Builder<BukkitCommandActor> builder = BukkitLamp.builder(createLampConfig());
        configurator.accept((Lamp.Builder) builder);
        return builder.build();
    }

    @Override
    public void describeCommandErrors(CommandErrorCatalog catalog) {
        SpigotCommandErrors.addTo(catalog);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommandActor networkConsoleActor(Lamp<? extends CommandActor> lamp) {
        return new SpigotConsoleActor((Lamp<BukkitCommandActor>) lamp);
    }

    @Override
    public boolean hasConsoleSender(CommandActor actor) {
        return actor instanceof BukkitCommandActor
                && ((BukkitCommandActor) actor).sender() instanceof ConsoleCommandSender;
    }

    private BukkitLampConfig<BukkitCommandActor> createLampConfig() {
        return BukkitLampConfig.<BukkitCommandActor>builder(plugin).build();
    }

    @Override
    public @Nullable AbstractPlayer getAbstractPlayer(UUID uuid, boolean queryMojang) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) return toAbstractPlayer(player);

        if (!queryMojang) return null;
        return offlineProfile(uuid, Bukkit.getOfflinePlayer(uuid));
    }

    @Override
    @SuppressWarnings("deprecation")
    public @Nullable AbstractPlayer getAbstractPlayer(String username, boolean queryMojang) {
        Player player = Bukkit.getPlayer(username);
        if (player != null) return toAbstractPlayer(player);

        if (!queryMojang) return null;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
        if (!offlinePlayer.hasPlayedBefore()) return null;
        return offlineProfile(offlinePlayer.getUniqueId(), offlinePlayer);
    }

    private static @Nullable AbstractPlayer offlineProfile(UUID uuid, OfflinePlayer offlinePlayer) {
        String name = offlinePlayer.getName();
        return name == null ? null : new AbstractPlayer(uuid, name, false);
    }

    @Override
    public @Nullable CirrusPlayerWrapper getPlayerWrapper(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? new SpigotPlayerWrapper(player) : null;
    }

    @Override
    public Collection<AbstractPlayer> getOnlinePlayers() {
        return Bukkit.getOnlinePlayers().stream()
            .map(this::toAbstractPlayer)
            .collect(Collectors.toList());
    }

    @Override
    public @Nullable AbstractPlayer getPlayer(UUID uuid) {
        return getAbstractPlayer(uuid, false);
    }

    @Override
    public int getMaxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public String getServerVersion() {
        return Bukkit.getVersion();
    }

    @Override
    public String getPlatformType() {
        return "spigot";
    }

    @Override
    public void runOnMainThread(Runnable task) {
        if (FoliaScheduler.isFolia()) {
            foliaScheduler.runGlobal(task);
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void kickPlayer(AbstractPlayer player, String reason) {
        if (player == null) return;
        Player bukkitPlayer = Bukkit.getPlayer(player.getUuid());
        if (bukkitPlayer != null && bukkitPlayer.isOnline()) bukkitPlayer.kickPlayer(Colors.get(StringUtil.unescapeNewlines(reason)));
    }

    @Override
    public String getServerName() {
        return configServerName;
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public boolean isPluginEnabled(String name) {
        return Bukkit.getPluginManager().isPluginEnabled(name);
    }

    @Override
    public PluginLogger getLogger() {
        return pluginLogger;
    }

    @Override
    public boolean assignsOfflineUuids() {
        return !Bukkit.getOnlineMode() && proxyForwarding == ProxyForwarding.NONE;
    }

    @Override
    public boolean isOnlineModeAuthenticated(UUID uuid) {
        if (uuid.version() == NAME_DERIVED_UUID_VERSION) return false;
        return Bukkit.getOnlineMode() || proxyForwarding.forwardsIdentitiesVerifiedByASharedSecret();
    }

    private AbstractPlayer toAbstractPlayer(Player player) {
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
        return new AbstractPlayer(player.getUniqueId(), player.getName(), ip, player.isOnline());
    }

    @Override
    public Collection<String> commandNames(String label) {
        return commandNames.of(label);
    }

    @Override
    public void dispatchPlayerCommand(UUID uuid, String command) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        if (FoliaScheduler.isFolia()) {
            foliaScheduler.runForEntity(player, () -> player.performCommand(command));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command));
        }
    }

    @Nullable String preLoginTexture(AsyncPlayerPreLoginEvent event) {
        try {
            return preLoginTextures.texturesOf(event);
        } catch (ReflectiveOperationException | ClassCastException e) {
            logger.log(Level.FINE, "Failed to read the pre-login skin texture for " + event.getUniqueId(), e);
            return null;
        }
    }

    @Override
    public @Nullable String getPlayerSkinTexture(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return null;
        try {
            return skinTextures.texturesOf(player);
        } catch (ReflectiveOperationException | ClassCastException e) {
            logger.log(Level.FINE, "Failed to read native skin texture for " + uuid, e);
            return null;
        }
    }

    @Override
    public void log(String msg) {
        logger.info(msg);
    }
}
