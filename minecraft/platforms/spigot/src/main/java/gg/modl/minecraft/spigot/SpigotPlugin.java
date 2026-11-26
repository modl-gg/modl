package gg.modl.minecraft.spigot;

import com.alessiodp.libby.BukkitLibraryManager;
import dev.simplix.cirrus.spigot.CirrusSpigot;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.features.AbstractServerFeatures;
import gg.modl.minecraft.features.config.ServerFeaturesConfig;
import gg.modl.minecraft.features.moderation.LocalModerationSink;
import gg.modl.minecraft.features.reporter.TicketCreator;
import gg.modl.minecraft.core.BackendServices;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.PluginLoader;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.boot.BackendSession;
import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.boot.CoreBootstrap;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.PluginLifecycle;
import gg.modl.minecraft.core.boot.RunningNotice;
import gg.modl.minecraft.core.boot.RuntimeLibraryLoader;
import gg.modl.minecraft.core.config.yaml.CoreConfigBootstrap;
import gg.modl.minecraft.core.connection.ConnectionConfig;
import gg.modl.minecraft.core.connection.ConnectionConfigException;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.imports.sql.LoadedDriverLibraries;
import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.spigot.boot.PolarHookLatch;
import gg.modl.minecraft.spigot.boot.SignedVelocityBootstrap;
import gg.modl.minecraft.spigot.boot.SpigotPluginHost;
import gg.modl.minecraft.spigot.boot.SpigotProxyForwarding;
import gg.modl.minecraft.spigot.boot.TicketCreatorFactory;
import gg.modl.minecraft.spigot.features.ServerFeaturesComponent;
import gg.modl.minecraft.spigot.features.SpigotFeaturesResources;
import gg.modl.minecraft.spigot.member.MemberMode;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import top.polar.api.loader.LoaderApi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SpigotPlugin extends JavaPlugin {
    private final CoreBootstrap core = CoreBootstrap.of(this::prepareCore, this::connectCore, this::stopCore);
    private final PolarHookLatch polarHooks = new PolarHookLatch();
    private final LoadedDriverLibraries driverLibraries = new LoadedDriverLibraries();
    private final PluginLogger pluginLogger = PluginLogger.fromJul(getLogger());
    private final SignedVelocityBootstrap signedVelocityBootstrap = new SignedVelocityBootstrap(this, pluginLogger);
    private @Nullable PreparedCore preparedCore;
    private @Nullable MemberMode memberMode;
    private boolean polarLoaderAvailable = false;
    private ProxyForwarding proxyForwarding = ProxyForwarding.NONE;
    private @Nullable PluginLifecycle lifecycle;

    @Override
    public synchronized void onLoad() {
        proxyForwarding = SpigotProxyForwarding.detect(getServer(), Paths.get("").toAbsolutePath(), pluginLogger);
        PluginLifecycle loaded = new PluginLifecycle(new SpigotPluginHost(this, pluginLogger), proxyForwarding);
        lifecycle = loaded;
        loaded.load(() -> {
            registerPolarLoaderCallback();
            RuntimeLibraryLoader libraries =
                    new RuntimeLibraryLoader(new BukkitLibraryManager(this), PlatformType.SPIGOT, getClass().getClassLoader());
            libraries.loadAll();
            driverLibraries.loaded(libraries);
        });
    }

    @Override
    public synchronized void onEnable() {
        PluginLifecycle loaded = loadedLifecycle();
        ConnectionConfig connectionConfig = loaded.config();
        if (connectionConfig != null && connectionConfig.getMode().isMember()) {
            enableMemberMode(loaded, connectionConfig);
            return;
        }
        loaded.enable(startupRequest(), core);
    }

    private PluginLifecycle loadedLifecycle() {
        PluginLifecycle loaded = lifecycle;
        if (loaded == null) throw new IllegalStateException("modl was enabled before the server loaded it");
        return loaded;
    }

    private StartupRequest startupRequest() {
        return new StartupRequest(PluginInfo.VERSION, "SPIGOT", getServer().getVersion(), getServer().getMaxPlayers());
    }

    private void prepareCore(CoreAvailability availability) {
        saveDefaultConfig();
        createLocaleFiles();
        CoreConfigBootstrap.updateRuntimeConfigs(getDataFolder().toPath(), pluginLogger);

        ServerFeaturesConfig featuresConfig = AbstractServerFeatures.loadConfig(new SpigotFeaturesResources(this),
                pluginLogger);
        SpigotPlatform platform = new SpigotPlatform(this, getLogger(), getDataFolder(),
                featuresConfig.serverNameOrDefault(), proxyForwarding);
        PluginLoader loader = PluginLoader.create(platform, getDataFolder().toPath(), new ChatMessageCache(),
                availability, driverLibraries, getConfig().getBoolean("debug", false),
                getConfig().getBoolean("server.query_mojang", false));
        preparedCore = new PreparedCore(loader, platform, featuresConfig);
    }

    private void connectCore(BackendSession session) {
        PreparedCore prepared = preparedCore;
        if (prepared == null) throw new IllegalStateException("the core connected before it was prepared");
        prepared.loader.connect(session, backend -> enableCoreServices(prepared, session, backend));
        getLogger().info(RunningNotice.of("Spigot"));
    }

    private void stopCore() {
        PreparedCore prepared = preparedCore;
        preparedCore = null;
        if (prepared != null) prepared.loader.shutdown();
    }

    private void enableMemberMode(PluginLifecycle connectionSource, ConnectionConfig connectionConfig) {
        try {
            signedVelocityBootstrap.init(proxyForwarding);
            memberMode = MemberMode.enable(this, connectionConfig, connectionSource.configPath(), startupRequest(), pluginLogger,
                    polarLoaderAvailable, polarHooks, () -> connectionSource.reloadConnectionConfig().getRemote());
            getLogger().info(RunningNotice.behindProxy());
        } catch (ConnectionConfigException invalid) {
            pluginLogger.severe(invalid.getMessage());
        } catch (RuntimeException | LinkageError failure) {
            pluginLogger.warning("modl failed to start in behind-proxy mode", failure);
        }
    }

    private void enableCoreServices(PreparedCore prepared, BackendSession session, BackendServices backend) {
        PluginLoader loader = prepared.loader;
        SpigotPlatform platform = prepared.platform;
        new CirrusSpigot(this).init();

        HttpClientHolder httpClientHolder = loader.getHttpClientHolder();
        TicketCreator ticketCreator = TicketCreatorFactory.standalone(this, httpClientHolder::getClient);
        ServerFeaturesComponent features = new ServerFeaturesComponent(this, prepared.featuresConfig, pluginLogger,
                polarLoaderAvailable);
        backend.onShutdown("disable the server features", features::disable);
        features.enableStandalone(ticketCreator, loader.getLocalMemberActionSender(), platform::getServerName,
                loader.getLocaleManager());
        features.uploadReplaysTo(session.getConnections());
        LocalModerationSink moderationSink = LocalModerationSink.attach(features.getModerationReconciler(),
                loader.getModerationPublisher());
        backend.onShutdown("stop feeding the moderation state to the server features", moderationSink::detach);
        String serverName = platform.getServerName();

        ReplayService replayService = features.getReplayService();
        if (replayService != null) {
            PluginServices services = PluginServices.get();
            services.setReplayService(replayService);
            backend.onShutdown("forget the replay service", () -> services.setReplayService(null));
        }
        backend.getFeatureSettings().bind(features);
        backend.onShutdown("stop applying the panel's replay settings", backend.getFeatureSettings()::unbind);

        DirectStatWipeExecutor directExecutor = new DirectStatWipeExecutor(features, serverName);
        backend.getSyncService().setStatWipeExecutor(directExecutor);

        LoginPipeline loginPipeline = new LoginPipeline(
                backend.getLoginService(), loader.getLoginCache(), backend.getPlayerSessionService());

        SpigotListener listener = new SpigotListener(
                platform, loader.getCache(), loader.getChatMessageCache(), loginPipeline, backend.getLoginFlow(),
                new VanillaLoginRefusals(platform::runOnMainThread));
        getServer().getPluginManager().registerEvents(listener, this);
        backend.onShutdown("unregister the player listener", () -> HandlerList.unregisterAll(listener));
        Listener loginValidation = LoginValidationListeners.register(this,
                new LoginAdmission(platform.getLogger(), loginPipeline), platform.getLogger());
        backend.onShutdown("unregister the login validation listener", () -> HandlerList.unregisterAll(loginValidation));
        SpigotChatListener chatListener = new SpigotChatListener(loader.getChatService(),
                loader.getCommandInterceptService(), loader.getChatCommandLogService(), platform::getServerName);
        getServer().getPluginManager().registerEvents(chatListener, this);
        backend.onShutdown("unregister the chat listener", () -> HandlerList.unregisterAll(chatListener));
        DeliveredChatListener deliveredChat = DeliveredChatListener.register(this, loader.getChatService(),
                platform::getServerName);
        backend.onShutdown("unregister the delivered chat listener", () -> HandlerList.unregisterAll(deliveredChat));

        backend.onShutdown("stop hooking Polar", polarHooks::featuresStopped);
        polarHooks.featuresStarted(features);
    }

    @Override
    public synchronized void onDisable() {
        signedVelocityBootstrap.shutdown();
        PluginLifecycle loaded = lifecycle;
        lifecycle = null;
        if (loaded != null) loaded.shutdown();
        stopMemberMode();
    }

    private void stopMemberMode() {
        MemberMode running = memberMode;
        memberMode = null;
        if (running != null) running.disable();
    }

    private void registerPolarLoaderCallback() {
        try {
            Class.forName("top.polar.api.loader.LoaderApi");
            polarLoaderAvailable = true;
            LoaderApi.registerEnableCallback(polarHooks::polarEnabled);
        } catch (ClassNotFoundException ignored) {}
    }

    private void createLocaleFiles() {
        try {
            File localeDir = new File(getDataFolder(), "locale");
            if (!localeDir.exists()) localeDir.mkdirs();

            File enUsFile = new File(localeDir, "en_US.yml");
            if (enUsFile.exists()) return;

            try (InputStream defaultLocale = getResource("locale/en_US.yml")) {
                if (defaultLocale != null) Files.copy(defaultLocale, enUsFile.toPath());
                else getLogger().warning("Default locale resource not found in JAR");
            }
        } catch (IOException e) {
            getLogger().severe("Failed to create locale files: " + e.getMessage());
        }
    }

    private static final class PreparedCore {
        private final PluginLoader loader;
        private final SpigotPlatform platform;
        private final ServerFeaturesConfig featuresConfig;

        private PreparedCore(PluginLoader loader, SpigotPlatform platform, ServerFeaturesConfig featuresConfig) {
            this.loader = loader;
            this.platform = platform;
            this.featuresConfig = featuresConfig;
        }
    }
}
