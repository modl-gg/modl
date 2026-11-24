package gg.modl.minecraft.velocity;

import com.alessiodp.libby.VelocityLibraryManager;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.simplix.cirrus.velocity.CirrusVelocity;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.BackendServices;
import gg.modl.minecraft.core.PluginConfiguration;
import gg.modl.minecraft.core.PluginLoader;
import gg.modl.minecraft.core.boot.BackendSession;
import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.boot.CoreBootstrap;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.PluginLifecycle;
import gg.modl.minecraft.core.boot.RunningNotice;
import gg.modl.minecraft.core.boot.RuntimeLibraryLoader;
import gg.modl.minecraft.core.config.yaml.CoreConfigBootstrap;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.packet.PacketInterceptionDecision;
import gg.modl.minecraft.core.packet.PacketInterceptionMode;
import gg.modl.minecraft.core.packet.PacketInterceptionPolicy;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.YamlValues;
import io.github._4drian3d.signedvelocity.velocity.SignedVelocity;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@Plugin(id = PluginInfo.ID,
        name = PluginInfo.NAME,
        version = PluginInfo.VERSION,
        authors = { PluginInfo.AUTHOR },
        description = PluginInfo.DESCRIPTION,
        url = PluginInfo.URL,
        dependencies = {
                @Dependency(id = PacketInterceptionPolicy.PACKETEVENTS_PLUGIN_ID, optional = true),
                @Dependency(id = "litebans", optional = true),
                @Dependency(id = "libertybans", optional = true),
                @Dependency(id = "banmanager", optional = true),
                @Dependency(id = "bansystem", optional = true)
        })
public final class VelocityPlugin {

    private final CoreBootstrap core = CoreBootstrap.of(this::prepareCore, this::connectCore, this::stopCore);

    private final ProxyServer server;
    private final Path folder;
    private final Logger logger;
    private final PluginLogger pluginLogger;
    private final PluginLifecycle lifecycle;
    private final LoadedDriverLibraries driverLibraries = new LoadedDriverLibraries();
    private @Nullable PreparedCore prepared;

    @Inject
    public VelocityPlugin(ProxyServer server, @DataDirectory Path folder, Logger logger) {
        this.server = server;
        this.folder = folder;
        this.logger = logger;
        this.pluginLogger = new VelocityPluginLogger(logger);
        this.lifecycle = new PluginLifecycle(new VelocityPluginHost(this, server, folder, pluginLogger),
                ProxyForwarding.NONE);
    }

    @Subscribe
    public synchronized void onProxyInitialize(ProxyInitializeEvent event) {
        lifecycle.load(this::loadLibraries);
        lifecycle.enable(new StartupRequest(PluginInfo.VERSION, "VELOCITY", server.getVersion().getVersion(),
                server.getConfiguration().getShowMaxPlayers()), core);
    }

    private void prepareCore(CoreAvailability availability) {
        createLocaleFiles();
        CoreConfigBootstrap.updateRuntimeConfigs(folder, pluginLogger);
        Map<String, Object> configuration = loadConfig();
        boolean packetInterception = decidePacketInterception(configuration);
        initSignedVelocity(packetInterception);
        if (packetInterception) new CirrusVelocity(this, server).init();

        VelocityPlatform platform = new VelocityPlatform(this, this.server, logger, folder.toFile(),
                YamlValues.nestedString(configuration, "server.name", PluginConfiguration.DEFAULT_SERVER_NAME),
                pluginLogger, packetInterception);
        PluginLoader pluginLoader = PluginLoader.create(platform, folder, new ChatMessageCache(), availability,
                driverLibraries,
                YamlValues.nestedBool(configuration, "debug", false),
                YamlValues.nestedBool(configuration, "server.query_mojang", false));
        prepared = new PreparedCore(platform, pluginLoader);
    }

    private void connectCore(BackendSession session) {
        PreparedCore core = preparedCore();
        core.pluginLoader.connect(session, backend -> enableCoreServices(core, backend));
        logger.info(RunningNotice.of("Velocity"));
    }

    private PreparedCore preparedCore() {
        PreparedCore core = prepared;
        if (core == null) throw new IllegalStateException("the modl core was connected before it was prepared");
        return core;
    }

    private void enableCoreServices(PreparedCore core, BackendServices backend) {
        PluginLoader pluginLoader = core.pluginLoader;
        LoginPipeline loginPipeline = new LoginPipeline(
                backend.getLoginService(), pluginLoader.getLoginCache(), backend.getPlayerSessionService());

        registerListener(backend, new JoinListener(
                pluginLoader.getCache(), logger, core.platform, loginPipeline, backend.getLoginFlow(),
                backend.getServerSwitchService(), pluginLoader.isDebugMode()));
        registerListener(backend, new ChatListener(pluginLoader.getChatService(),
                pluginLoader.getCommandInterceptService(), pluginLoader.getChatCommandLogService()));
    }

    private void stopCore() {
        PreparedCore core = prepared;
        prepared = null;
        if (core != null) core.pluginLoader.shutdown();
    }

    @Subscribe
    public synchronized void onProxyShutdown(ProxyShutdownEvent event) {
        lifecycle.shutdown();
    }

    private void registerListener(BackendServices backend, Object listener) {
        server.getEventManager().register(this, listener);
        backend.onShutdown("unregister the " + listener.getClass().getSimpleName(),
                () -> server.getEventManager().unregisterListener(this, listener));
    }

    private void initSignedVelocity(boolean packetInterceptionEnabled) {
        if (server.getPluginManager().getPlugin("signedvelocity").isPresent()) {
            logger.info("[SignedVelocity] Using standalone SignedVelocity plugin");
            return;
        }

        SignedVelocity sv = new SignedVelocity(server, this, logger);
        sv.init();
        if (packetInterceptionEnabled) sv.enforceSecureChat();
        logger.debug("[SignedVelocity] Embedded listeners registered");
    }

    private boolean decidePacketInterception(Map<String, Object> configuration) {
        PacketInterceptionDecision decision = PacketInterceptionPolicy.decide(
                PacketInterceptionMode.fromConfig(YamlValues.nested(configuration, PacketInterceptionPolicy.CONFIG_KEY)),
                pluginId -> server.getPluginManager().getPlugin(pluginId)
                        .map(container -> container.getDescription().getVersion().orElse(""))
                        .orElse(null));
        decision.report(pluginLogger);
        return decision.isEnabled();
    }

    private void loadLibraries() {
        RuntimeLibraryLoader libraries = new RuntimeLibraryLoader(
                new VelocityLibraryManager<>(this, logger, folder, server.getPluginManager()),
                PlatformType.VELOCITY, getClass().getClassLoader());
        libraries.loadAll();
        driverLibraries.loaded(libraries);
    }

    private Map<String, Object> loadConfig() {
        try {
            if (!Files.exists(folder)) Files.createDirectories(folder);
            Path configFile = folder.resolve("config.yml");
            if (!Files.exists(configFile)) createDefaultConfig(configFile);

            Yaml yaml = new Yaml();
            try (InputStream inputStream = Files.newInputStream(configFile)) {
                Map<String, Object> loaded = yaml.load(inputStream);
                if (loaded == null) {
                    logger.warn("Configuration file is empty, using defaults");
                    return mapOf();
                }
                logger.debug("Configuration loaded successfully");
                return loaded;
            }
        } catch (IOException e) {
            logger.error("Failed to load configuration", e);
            return mapOf();
        }
    }

    private void createDefaultConfig(Path configFile) throws IOException {
        try (InputStream defaultConfig = getClass().getResourceAsStream("/config.yml")) {
            if (defaultConfig != null) Files.copy(defaultConfig, configFile);
            else logger.warn("Default config resource not found in JAR");
        }
    }

    private void createLocaleFiles() {
        try {
            Path localeDir = folder.resolve("locale");
            if (!Files.exists(localeDir)) Files.createDirectories(localeDir);

            Path enUsFile = localeDir.resolve("en_US.yml");
            if (Files.exists(enUsFile)) return;

            try (InputStream defaultLocale = getClass().getResourceAsStream("/locale/en_US.yml")) {
                if (defaultLocale != null) Files.copy(defaultLocale, enUsFile);
                else logger.warn("Default locale resource not found in JAR");
            }
        } catch (IOException e) {
            logger.error("Failed to create locale files", e);
        }
    }

    private static final class PreparedCore {
        private final VelocityPlatform platform;
        private final PluginLoader pluginLoader;

        private PreparedCore(VelocityPlatform platform, PluginLoader pluginLoader) {
            this.platform = platform;
            this.pluginLoader = pluginLoader;
        }
    }
}
