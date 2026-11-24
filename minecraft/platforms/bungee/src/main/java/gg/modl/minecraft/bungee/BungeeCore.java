package gg.modl.minecraft.bungee;

import dev.simplix.cirrus.bungee.CirrusBungee;
import gg.modl.minecraft.core.BackendServices;
import gg.modl.minecraft.core.PluginConfiguration;
import gg.modl.minecraft.core.PluginLoader;
import gg.modl.minecraft.core.boot.BackendSession;
import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.boot.CoreBootstrap;
import gg.modl.minecraft.core.boot.RunningNotice;
import gg.modl.minecraft.core.config.yaml.CoreConfigBootstrap;
import gg.modl.minecraft.core.imports.sql.DriverLibraries;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.packet.PacketInterceptionDecision;
import gg.modl.minecraft.core.packet.PacketInterceptionMode;
import gg.modl.minecraft.core.packet.PacketInterceptionPolicy;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.util.PluginLogger;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;

final class BungeeCore implements CoreBootstrap {
    private final Plugin plugin;
    private final PluginLogger pluginLogger;
    private final DriverLibraries driverLibraries;
    private @Nullable PreparedCore prepared;

    BungeeCore(Plugin plugin, PluginLogger pluginLogger, DriverLibraries driverLibraries) {
        this.plugin = plugin;
        this.pluginLogger = pluginLogger;
        this.driverLibraries = driverLibraries;
    }

    @Override
    public void prepare(CoreAvailability availability) {
        createLocaleFiles();
        CoreConfigBootstrap.updateRuntimeConfigs(plugin.getDataFolder().toPath(), pluginLogger);
        Configuration configuration = loadConfig();
        boolean packetInterception = decidePacketInterception(configuration);
        if (packetInterception) new CirrusBungee(plugin).init();

        BungeePlatform platform = new BungeePlatform(plugin, plugin.getLogger(), plugin.getDataFolder(),
                configuration.getString("server.name", PluginConfiguration.DEFAULT_SERVER_NAME), packetInterception);
        PluginLoader loader = PluginLoader.create(platform, plugin.getDataFolder().toPath(), new ChatMessageCache(),
                availability, driverLibraries, configuration.getBoolean("debug", false),
                configuration.getBoolean("server.query_mojang", false));
        prepared = new PreparedCore(platform, loader);
    }

    @Override
    public void connect(BackendSession session) {
        PreparedCore core = preparedCore();
        core.loader.connect(session, backend -> enableCoreServices(core, backend));
        plugin.getLogger().info(RunningNotice.of("BungeeCord"));
    }

    @Override
    public void stop() {
        PreparedCore core = prepared;
        prepared = null;
        if (core != null) core.loader.shutdown();
    }

    private PreparedCore preparedCore() {
        PreparedCore core = prepared;
        if (core == null) throw new IllegalStateException("the modl core was connected before it was prepared");
        return core;
    }

    private void enableCoreServices(PreparedCore core, BackendServices backend) {
        PluginLoader loader = core.loader;
        LoginPipeline loginPipeline = new LoginPipeline(
                backend.getLoginService(), loader.getLoginCache(), backend.getPlayerSessionService());

        registerListener(backend, new BungeeListener(
                core.platform, loader.getCache(), plugin, loginPipeline,
                backend.getLoginFlow(), backend.getServerSwitchService()));

        registerListener(backend, new BungeeChatListener(loader.getChatService(), loader.getCommandInterceptService(),
                loader.getChatCommandLogService(), loader.getAsyncCommandExecutor(), plugin.getProxy()));
    }

    private void registerListener(BackendServices backend, Listener listener) {
        plugin.getProxy().getPluginManager().registerListener(plugin, listener);
        backend.onShutdown("unregister the " + listener.getClass().getSimpleName(),
                () -> plugin.getProxy().getPluginManager().unregisterListener(listener));
    }

    private boolean decidePacketInterception(Configuration configuration) {
        PacketInterceptionDecision decision = PacketInterceptionPolicy.decide(
                PacketInterceptionMode.fromConfig(configuration.get(PacketInterceptionPolicy.CONFIG_KEY)),
                pluginId -> plugin.getProxy().getPluginManager().getPlugins().stream()
                        .filter(installed -> installed.getDescription().getName().equalsIgnoreCase(pluginId))
                        .map(installed -> installed.getDescription().getVersion())
                        .findFirst()
                        .orElse(null));
        decision.report(pluginLogger);
        return decision.isEnabled();
    }

    private Configuration loadConfig() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdir();
        File file = new File(dataFolder, "config.yml");

        if (!file.exists()) {
            try (InputStream defaultConfig = plugin.getResourceAsStream("config.yml")) {
                if (defaultConfig != null) Files.copy(defaultConfig, file.toPath());
                else plugin.getLogger().warning("Default config resource not found in JAR");
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create default config file", e);
            }
        }

        try {
            return ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load configuration", e);
            return new Configuration();
        }
    }

    private void createLocaleFiles() {
        try {
            File localeDir = new File(plugin.getDataFolder(), "locale");
            if (!localeDir.exists()) localeDir.mkdirs();

            File enUsFile = new File(localeDir, "en_US.yml");
            if (enUsFile.exists()) return;

            try (InputStream defaultLocale = plugin.getResourceAsStream("locale/en_US.yml")) {
                if (defaultLocale != null) Files.copy(defaultLocale, enUsFile.toPath());
                else plugin.getLogger().warning("Default locale resource not found in JAR");
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to create locale files: " + e.getMessage());
        }
    }

    private static final class PreparedCore {
        private final BungeePlatform platform;
        private final PluginLoader loader;

        private PreparedCore(BungeePlatform platform, PluginLoader loader) {
            this.platform = platform;
            this.loader = loader;
        }
    }
}
