package gg.modl.minecraft.core;

import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.dynamic.Annotations;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.parameter.ParameterType;
import revxrsal.commands.parameter.StringParameterType;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.PunishmentTypeClassifiers;
import gg.modl.minecraft.api.RegistryPunishmentTypeClassifier;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.boot.BackendSession;
import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.chat.CommandInterceptService;
import gg.modl.minecraft.core.chat.CommandMatcher;
import gg.modl.minecraft.core.chat.CredentialCommandRedactor;
import gg.modl.minecraft.core.config.ConfigManager;
import gg.modl.minecraft.core.config.ConfigurationReload;
import gg.modl.minecraft.core.config.RuntimeConfigSource;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.impl.commands.staff.AltsCommand;
import gg.modl.minecraft.core.impl.commands.staff.ChatCommand;
import gg.modl.minecraft.core.impl.commands.staff.ChatLogsCommand;
import gg.modl.minecraft.core.impl.commands.staff.CommandLogsCommand;
import gg.modl.minecraft.core.impl.commands.staff.FreezeCommand;
import gg.modl.minecraft.core.impl.commands.staff.HistoryCommand;
import gg.modl.minecraft.core.impl.commands.staff.InspectCommand;
import gg.modl.minecraft.core.impl.commands.staff.InterceptNetworkChatCommand;
import gg.modl.minecraft.core.impl.commands.staff.LocalChatCommand;
import gg.modl.minecraft.core.impl.commands.ModlHelpCommand;
import gg.modl.minecraft.core.impl.commands.ModlPanelCommand;
import gg.modl.minecraft.core.impl.commands.staff.ModlImportCommand;
import gg.modl.minecraft.core.impl.commands.staff.ModlStatusCommand;
import gg.modl.minecraft.core.impl.commands.staff.ModlReloadCommand;
import gg.modl.minecraft.core.impl.commands.staff.NotesCommand;
import gg.modl.minecraft.core.impl.commands.staff.PunishmentActionCommand;
import gg.modl.minecraft.core.impl.commands.staff.ReportsCommand;
import gg.modl.minecraft.core.impl.commands.staff.StaffChatCommand;
import gg.modl.minecraft.core.impl.commands.staff.StaffCommand;
import gg.modl.minecraft.core.impl.commands.staff.StaffListCommand;
import gg.modl.minecraft.core.impl.commands.staff.StaffModeCommand;
import gg.modl.minecraft.core.impl.commands.staff.ReplayCommand;
import gg.modl.minecraft.core.impl.commands.staff.TargetCommand;
import gg.modl.minecraft.core.command.CommandRoots;
import gg.modl.minecraft.core.command.ConfiguredCommandAliases;
import gg.modl.minecraft.core.command.ConsumeRemaining;
import gg.modl.minecraft.core.command.PlayerQuerySuggestions;
import gg.modl.minecraft.core.command.error.CommandErrorLocalization;
import gg.modl.minecraft.core.impl.commands.player.ApplyCommand;
import gg.modl.minecraft.core.impl.commands.player.BugReportCommand;
import gg.modl.minecraft.core.impl.commands.player.ChatReportCommand;
import gg.modl.minecraft.core.impl.commands.player.ClaimTicketCommand;
import gg.modl.minecraft.core.impl.commands.player.HackReportCommand;
import gg.modl.minecraft.core.impl.commands.player.ReportCommand;
import gg.modl.minecraft.core.impl.commands.player.SupportCommand;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.impl.commands.staff.VanishCommand;
import gg.modl.minecraft.core.impl.commands.staff.VerifyCommand;
import gg.modl.minecraft.core.impl.commands.player.IAmMutedCommand;
import gg.modl.minecraft.core.impl.commands.player.StandingCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.BanCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.BlacklistCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.KickCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.MuteCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.PardonCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.PunishCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.UnbanCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.UnmuteCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.WarnCommand;
import gg.modl.minecraft.core.impl.menus.util.ChatInputManager;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.locale.MessageRenderer;
import gg.modl.minecraft.core.network.CommandRootRegistry;
import gg.modl.minecraft.core.network.LocalMemberActionSender;
import gg.modl.minecraft.core.network.MemberActionExecutor;
import gg.modl.minecraft.core.network.ModerationPublisher;
import gg.modl.minecraft.core.network.ModerationSessionService;
import gg.modl.minecraft.core.network.NetworkAvailability;
import gg.modl.minecraft.core.network.SwitchableNetworkAvailability;
import gg.modl.minecraft.core.staff.action.FreezeActions;
import gg.modl.minecraft.core.staff.action.HackReportActions;
import gg.modl.minecraft.core.staff.action.InspectActions;
import gg.modl.minecraft.core.staff.action.StaffActionContext;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.staff.action.StaffMenuActions;
import gg.modl.minecraft.core.staff.action.StaffModeActions;
import gg.modl.minecraft.core.staff.action.StaffSessionRevocation;
import gg.modl.minecraft.core.staff.action.TargetActions;
import gg.modl.minecraft.core.staff.action.VanishActions;
import gg.modl.minecraft.core.service.ChatCommandLogPolicy;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.service.ChatManagementService;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.service.MaintenanceService;
import gg.modl.minecraft.core.service.NetworkChatInterceptService;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.service.StaffChatService;
import gg.modl.minecraft.core.service.UpdateNotice;
import gg.modl.minecraft.core.impl.menus.util.PlayerHeadItemBuilder;
import gg.modl.minecraft.core.util.Java8Collections;
import gg.modl.minecraft.core.imports.ImportSourceCatalog;
import gg.modl.minecraft.core.imports.Imports;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.sql.DriverLibraries;
import gg.modl.minecraft.core.imports.sql.JdbcConnections;
import gg.modl.minecraft.core.util.DateFormatter;
import gg.modl.minecraft.core.staff.StaffAudiences;
import gg.modl.minecraft.core.staff.StaffPermissionService;
import gg.modl.minecraft.core.chat.ChatService;
import gg.modl.minecraft.core.integration.mojang.MojangProfiles;
import gg.modl.minecraft.core.login.ForwardedAddressNotice;
import gg.modl.minecraft.core.login.LoginSkins;
import gg.modl.minecraft.core.player.PlayerLookupService;
import gg.modl.minecraft.core.punishment.PunishmentActionMessageService;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.punishment.PunishmentTypeCacheManager;
import lombok.AccessLevel;
import lombok.Getter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.TeardownStack;

@Getter
public class PluginLoader {
    private final Lamp<? extends CommandActor> lamp;
    private final HttpClientHolder httpClientHolder;
    private final CachedProfileRegistry cachedProfileRegistry;
    private final Cache cache;
    private final UpdateNotice updateNotice;
    private final ChatMessageCache chatMessageCache;
    private final LocaleManager localeManager;
    private final LoginCache loginCache;
    private final LoginSkins loginSkins;
    private final ForwardedAddressNotice forwardedAddressNotice;
    @Getter(AccessLevel.NONE)
    private final ConfigurationReload configurationReload;
    private final AsyncCommandExecutor asyncCommandExecutor;
    private final Path dataDirectory;
    private final PluginLogger logger;
    private final ConfigManager configManager;
    private final StaffChatService staffChatService;
    private final ChatManagementService chatManagementService;
    private final Staff2faService staff2faService;
    private final MaintenanceService maintenanceService;
    private final NetworkChatInterceptService networkChatInterceptService;
    private final ChatCommandLogService chatCommandLogService;
    private final CommandInterceptService commandInterceptService;
    private final Map<String, String> commandAliases;
    private final ModerationSessionService moderation;
    private final ModerationPublisher moderationPublisher;
    private final SwitchableNetworkAvailability networkAvailability;
    private final MemberActionExecutor memberActionExecutor;
    private final LocalMemberActionSender localMemberActionSender;
    private final boolean queryMojang, debugMode;
    private final PunishmentMessageService punishmentMessageService;
    private final PunishmentActionMessageService punishmentActionMessageService;
    private final PunishmentTypeCacheManager punishmentTypeCacheManager;
    private final ChatService chatService;
    private final StaffPermissionService staffPermissionService;
    private final PlayerLookupService playerLookupService;
    private final StaffAudiences staffAudiences;
    private final PanelUrl panelUrl;
    @Getter(AccessLevel.PACKAGE)
    private final Platform platform;
    @Getter(AccessLevel.PACKAGE)
    private final ConnectedImportBackend importBackend;
    @Getter(AccessLevel.PACKAGE)
    private final PluginServices pluginServices;
    @Getter(AccessLevel.NONE)
    private final Imports imports;
    @Getter(AccessLevel.NONE)
    private final PluginConfiguration.UpdateCheckerConfig updateCheckerConfig;
    @Getter(AccessLevel.NONE)
    private final @Nullable PunishCommand punishCommand;
    @Getter(AccessLevel.NONE)
    private final CommandRootRegistry.Builder commandRoots;
    @Getter(AccessLevel.NONE)
    private final TeardownStack teardown;
    @Getter(AccessLevel.NONE)
    private boolean connected;

    public ModlHttpClient getHttpClient() {
        return httpClientHolder.getClient();
    }

    public static PluginLoader create(Platform platform, Path dataDirectory, ChatMessageCache chatMessageCache,
                                      CoreAvailability availability, DriverLibraries driverLibraries, boolean debugMode,
                                      boolean queryMojang) {
        TeardownStack teardown = new TeardownStack(platform.getLogger());
        try {
            return new PluginLoader(teardown, platform, dataDirectory, chatMessageCache, availability, driverLibraries,
                    debugMode, queryMojang);
        } catch (RuntimeException | Error failure) {
            teardown.unwind();
            throw failure;
        }
    }

    private PluginLoader(TeardownStack teardown, Platform platform, Path dataDirectory, ChatMessageCache chatMessageCache,
                         CoreAvailability availability, DriverLibraries driverLibraries, boolean debugMode,
                         boolean queryMojang) {
        this.teardown = teardown;
        teardown.pushFinal("stop the shared lookup executors", PluginLoader::shutdownSharedExecutors);
        this.platform = platform;
        this.dataDirectory = dataDirectory;
        this.panelUrl = PanelUrl.unresolved();
        this.debugMode = debugMode;
        this.chatMessageCache = chatMessageCache;
        this.queryMojang = queryMojang;
        this.asyncCommandExecutor = new AsyncCommandExecutor();
        teardown.push("stop the async command executor", asyncCommandExecutor::shutdown);
        cachedProfileRegistry = new CachedProfileRegistry();
        cache = new Cache(cachedProfileRegistry);
        cache.setQueryMojang(queryMojang);
        loginCache = new LoginCache();
        teardown.push("stop the login cache", loginCache::shutdown);

        this.configManager = new ConfigManager(dataDirectory, platform.getLogger());
        cache.setPunishmentTypeItems(configManager.getPunishmentTypeItems());
        this.httpClientHolder = new HttpClientHolder();
        this.logger = platform.getLogger();
        this.loginSkins = new LoginSkins(uuid -> MojangProfiles.client().get(uuid));
        this.forwardedAddressNotice = new ForwardedAddressNotice(logger);

        RuntimeConfigSource runtimeConfigSource = configManager.getRuntimeConfigSource();
        Map<String, Object> configYml = runtimeConfigSource.root();
        String configuredLocale = PluginConfiguration.readLocaleFromConfig(configYml);

        this.localeManager = new LocaleManager(configuredLocale);
        this.localeManager.setMissingKeyReporter(logger::warning);
        Path localeFile = dataDirectory.resolve("locale").resolve(configuredLocale + ".yml");
        if (Files.exists(localeFile)) {
            if (debugMode) logger.info("Loading locale from external file: " + localeFile);
            this.localeManager.loadFromFile(localeFile);
        }
        this.localeManager.setRenderer(new MessageRenderer());
        applyLocaleConfig(configYml, logger);
        this.punishmentMessageService = new PunishmentMessageService(this.localeManager, panelUrl);

        this.importBackend = new ConnectedImportBackend(httpClientHolder);
        JdbcConnections importConnections = new JdbcConnections(driverLibraries, Imports.nativeFolder(dataDirectory.toAbsolutePath()));
        teardown.push("release the import database drivers", importConnections::close);
        this.imports = createImports(importConnections);
        teardown.push("stop the import", imports::shutdown);

        this.staff2faService = new Staff2faService(cachedProfileRegistry, configManager.getStaff2faConfig());
        this.chatCommandLogService = new ChatCommandLogService();

        this.commandAliases = PluginConfiguration.loadCommandAliases(configYml, logger);
        ConfiguredCommandAliases configuredCommandAliases = new ConfiguredCommandAliases(commandAliases);
        this.punishmentActionMessageService = new PunishmentActionMessageService(
                platform, this.localeManager, configuredCommandAliases.primaryAlias("punishment_action"));

        this.updateCheckerConfig = PluginConfiguration.loadUpdateCheckerConfig(configYml, logger);
        this.updateNotice = new UpdateNotice(logger);

        PlayerLookupService playerLookup = new PlayerLookupService(platform, httpClientHolder, queryMojang);
        this.playerLookupService = playerLookup;

        this.staffChatService = new StaffChatService(cachedProfileRegistry);
        this.chatManagementService = new ChatManagementService(cachedProfileRegistry);
        this.maintenanceService = new MaintenanceService();
        this.networkChatInterceptService = new NetworkChatInterceptService(cachedProfileRegistry);
        this.moderation = new ModerationSessionService();
        this.commandInterceptService = new CommandInterceptService(cache, moderation, this.localeManager,
                this.punishmentMessageService, CommandMatcher.none());
        applyChatAndCommandSettings(configYml);
        this.moderationPublisher = ModerationPublisher.start(moderation::snapshot, logger);
        teardown.push("stop the moderation publisher", moderationPublisher::stop);
        moderation.addChangeListener(moderationPublisher::changed);
        this.networkAvailability = new SwitchableNetworkAvailability(
                platform.hostsNetworkMembers() ? NetworkAvailability.NOWHERE : NetworkAvailability.EVERYWHERE);
        ChatInputManager chatInputManager = new ChatInputManager(platform, this.localeManager);

        StaffActionGate staffActionGate = new StaffActionGate(availability, cache, this.localeManager,
                this.staff2faService, platform::isLocalConsole);
        StaffActionContext staffActions = new StaffActionContext(platform, cache, this.localeManager, moderation);
        StaffModeActions staffModeActions = new StaffModeActions(staffActions,
                () -> configManager.getStaffModeSettings().isVanishOnEnable());
        TargetActions targetActions = new TargetActions(staffActions, staffModeActions);
        VanishActions vanishActions = new VanishActions(staffActions);
        StaffSessionRevocation staffSessionRevocation = new StaffSessionRevocation(staffActions, staffActionGate,
                staffModeActions, vanishActions);
        this.staffPermissionService = new StaffPermissionService(httpClientHolder, cache, logger, debugMode,
                staffSessionRevocation::endUnauthorizedSessions);

        this.configurationReload = new ConfigurationReload(this.localeManager, cache, this::reloadRuntimeConfiguration);
        this.pluginServices = new PluginServices(cache, this.localeManager, this.staff2faService,
                staffActionGate, targetActions, chatInputManager,
                this.punishmentMessageService, this.punishmentActionMessageService, panelUrl, configManager,
                configurationReload);
        PluginServices.install(pluginServices);
        teardown.pushFinal("uninstall the plugin services", () -> PluginServices.uninstall(pluginServices));
        platform.setStaffAudience(pluginServices);
        this.staffAudiences = new StaffAudiences(pluginServices, cache);

        CommandAccessPolicy accessPolicy = new CommandAccessPolicy(staffActionGate);
        this.commandRoots = CommandRootRegistry.builder(configuredCommandAliases);

        this.lamp = platform.buildLamp(builder -> {
            CommandErrorLocalization.install(builder, localeManager, logger, platform::describeCommandErrors);
            builder.annotationReplacer(Command.class, (element, annotation) ->
                remapCommandAnnotation(annotation, configuredCommandAliases));
            accessPolicy.install(builder);
            builder.suggestionProviders(suggestionProviders -> suggestionProviders.addProviderFactoryLast((type, annotations, lamp) -> {
                if (type instanceof Class) {
                    Class<?> clazz = (Class<?>) type;
                    if (clazz == AbstractPlayer.class || clazz == Account.class) {
                        return PlayerQuerySuggestions.onlinePlayerNames(platform);
                    }
                }

                if (!(type instanceof Class) || type != String.class) {
                    return null;
                }

                Named named = annotations.get(Named.class);
                if (named != null && ModlImportCommand.SOURCE_PARAMETER.equals(named.value())) {
                    return context -> imports.detectedSources();
                }
                if (named == null || !"player".equals(named.value())) {
                    return null;
                }

                return PlayerQuerySuggestions.onlinePlayerNames(platform);
            }));
            builder.parameterTypes(types -> {
                types.addParameterTypeFactory(new ParameterType.Factory<CommandActor>() {
                    @Override
                    public <T> @Nullable ParameterType<CommandActor, T> create(Type type, AnnotationList annotations, Lamp<CommandActor> lamp) {
                        if (!(type instanceof Class) || type != String.class) return null;
                        if (!annotations.contains(ConsumeRemaining.class)) return null;
                        @SuppressWarnings("unchecked")
                        ParameterType<CommandActor, T> greedyString = (ParameterType<CommandActor, T>) StringParameterType.greedy();
                        return greedyString;
                    }
                });
                types.addParameterType(AbstractPlayer.class, (input, context) -> {
                    accessPolicy.requireRunning();
                    String name = input.readString();
                    AbstractPlayer player = playerLookup.fetchPlayer(name);
                    if (player == null) throw CommandAccessPolicy.deny(localeManager.getMessage("general.player_not_found"));
                    return player;
                });
                types.addParameterType(Account.class, (input, context) -> {
                    accessPolicy.requireRunning();
                    String name = input.readString();
                    Account account = playerLookup.fetchAccount(name);
                    if (account == null) throw CommandAccessPolicy.deny(localeManager.getMessage("general.player_not_found"));
                    return account;
                });
            });
        });
        teardown.push("unregister the commands", () -> platform.unregisterCommands(lamp));

        RegistryPunishmentTypeClassifier punishmentTypeClassifier = new RegistryPunishmentTypeClassifier();
        PunishmentTypeClassifiers.install(punishmentTypeClassifier);
        teardown.pushFinal("uninstall the punishment type classifier",
                () -> PunishmentTypeClassifiers.uninstall(punishmentTypeClassifier));
        PunishCommand punish = new PunishCommand(httpClientHolder, platform, cache, this.localeManager,
                punishmentTypeClassifier, this.staffPermissionService);
        this.punishCommand = registerIfEnabled(configuredCommandAliases, punish) ? punish : null;

        registerIfEnabled(configuredCommandAliases, new ModlHelpCommand(cache, this.localeManager,
                platform::isLocalConsole));
        registerIfEnabled(configuredCommandAliases, new ModlImportCommand(imports));
        registerIfEnabled(configuredCommandAliases, new ModlStatusCommand(platform, httpClientHolder, availability, panelUrl,
                this.localeManager));
        registerIfEnabled(configuredCommandAliases, new ModlReloadCommand(configurationReload));
        registerIfEnabled(configuredCommandAliases, new ModlPanelCommand(platform, httpClientHolder, this.localeManager,
                configManager, this.staffPermissionService));
        registerIfEnabled(configuredCommandAliases, new BanCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new MuteCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new KickCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new BlacklistCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new PardonCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new UnbanCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new UnmuteCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new WarnCommand(httpClientHolder, platform, cache, this.localeManager,
                staffAudiences));
        registerIfEnabled(configuredCommandAliases, new IAmMutedCommand(platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new StandingCommand(httpClientHolder, platform, this.localeManager, configManager, cache));

        TicketService ticketService = new TicketService(cache, httpClientHolder, platform, this.localeManager);
        pluginServices.setTicketService(ticketService);
        registerIfEnabled(configuredCommandAliases, new ReportCommand(asyncCommandExecutor, platform, httpClientHolder, this.localeManager, chatMessageCache, ticketService, configManager));
        registerIfEnabled(configuredCommandAliases, new ChatReportCommand(platform, this.localeManager, chatMessageCache, ticketService));
        HackReportActions hackReportActions = new HackReportActions(staffActions, ticketService, PluginServices::replay);
        registerIfEnabled(configuredCommandAliases, new HackReportCommand(platform, this.localeManager, hackReportActions));
        registerIfEnabled(configuredCommandAliases, new ApplyCommand(ticketService));
        registerIfEnabled(configuredCommandAliases, new BugReportCommand(ticketService));
        registerIfEnabled(configuredCommandAliases, new SupportCommand(ticketService));
        registerIfEnabled(configuredCommandAliases, new ClaimTicketCommand(platform, httpClientHolder, this.localeManager, ticketService));

        this.punishmentTypeCacheManager = new PunishmentTypeCacheManager();

        InspectActions inspectActions = new InspectActions(staffActions, httpClientHolder);
        InspectCommand inspectCommand = new InspectCommand(httpClientHolder, platform, cache, this.localeManager, panelUrl,
                this.punishmentTypeCacheManager, inspectActions);
        registerIfEnabled(configuredCommandAliases, inspectCommand);
        StaffMenuActions staffMenuActions = new StaffMenuActions(staffActions, asyncCommandExecutor, httpClientHolder,
                panelUrl);
        registerIfEnabled(configuredCommandAliases, new StaffCommand(staffMenuActions));
        HistoryCommand historyCommand = new HistoryCommand(httpClientHolder, platform, cache, this.localeManager, this.punishmentTypeCacheManager);
        registerIfEnabled(configuredCommandAliases, historyCommand);
        registerIfEnabled(configuredCommandAliases, new AltsCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new NotesCommand(httpClientHolder, platform, cache, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new ReportsCommand(httpClientHolder, platform, cache, this.localeManager, panelUrl));
        registerIfEnabled(configuredCommandAliases, new PunishmentActionCommand(httpClientHolder, platform, cache, this.localeManager, panelUrl));

        this.chatService = new ChatService(platform, cache, this.localeManager, chatMessageCache,
                staffChatService, configManager.getStaffChatConfig(), chatManagementService,
                moderation, chatCommandLogService, networkChatInterceptService, this.punishmentMessageService,
                staffActionGate);

        registerIfEnabled(configuredCommandAliases, new StaffChatCommand(platform, cache, this.localeManager, staffChatService, configManager.getStaffChatConfig()));
        registerIfEnabled(configuredCommandAliases, new LocalChatCommand(platform, cache, this.localeManager, staffChatService));
        registerIfEnabled(configuredCommandAliases, new ChatCommand(platform, cache, this.localeManager, staffChatService, chatManagementService,
                configManager.getStaffChatConfig(), configManager.getChatManagementConfig()));
        registerIfEnabled(configuredCommandAliases, new StaffListCommand(platform, cache, this.localeManager, moderation, httpClientHolder, panelUrl));
        registerIfEnabled(configuredCommandAliases, new VerifyCommand(platform, this.localeManager, staff2faService, httpClientHolder));
        registerIfEnabled(configuredCommandAliases, new InterceptNetworkChatCommand(networkChatInterceptService, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new ChatLogsCommand(httpClientHolder, chatCommandLogService, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new CommandLogsCommand(httpClientHolder, chatCommandLogService, this.localeManager));
        FreezeActions freezeActions = new FreezeActions(staffActions, networkAvailability);
        registerIfEnabled(configuredCommandAliases, new FreezeCommand(freezeActions));
        registerIfEnabled(configuredCommandAliases, new StaffModeCommand(staffModeActions));
        registerIfEnabled(configuredCommandAliases, new VanishCommand(vanishActions));
        registerIfEnabled(configuredCommandAliases, new TargetCommand(targetActions, this.localeManager));
        registerIfEnabled(configuredCommandAliases, new ReplayCommand(platform, this.localeManager, panelUrl));

        registerAsyncAliases(configuredCommandAliases,
            "punish", "ban", "mute", "kick", "blacklist", "pardon", "unban", "unmute", "warn",
            "inspect", "staffmenu", "history", "alts", "notes", "reports", "iammuted",
            "report", "chatreport", "hackreport", "apply", "bugreport", "support", "tclaim", "standing",
            "punishment_action", "staffchat", "localchat", "chat", "stafflist", "freeze",
            "staffmode", "vanish", "target", "verify", "interceptnetworkchat", "chatlogs",
            "commandlogs", "replay");
        platform.finalizeLampRegistration(lamp);

        this.memberActionExecutor = MemberActionExecutor.builder()
                .asyncExecutor(asyncCommandExecutor::execute)
                .platform(platform)
                .localeManager(this.localeManager)
                .moderation(moderation)
                .commandRoots(commandRoots.build())
                .gate(staffActionGate)
                .consoleCommands(this::dispatchModlConsoleCommand)
                .logger(logger)
                .freezeActions(freezeActions)
                .vanishActions(vanishActions)
                .targetActions(targetActions)
                .staffMenuActions(staffMenuActions)
                .inspectActions(inspectActions)
                .hackReportActions(hackReportActions)
                .staffModeActions(staffModeActions)
                .build();
        this.localMemberActionSender = new LocalMemberActionSender(memberActionExecutor, platform::getServerName);
    }

    private static Collection<Annotation> remapCommandAnnotation(Command annotation, ConfiguredCommandAliases commandAliases) {
        String[] remappedValues = commandAliases.resolveCommandValues(annotation.value());
        if (Arrays.equals(annotation.value(), remappedValues)) return Collections.singletonList(annotation);
        if (remappedValues.length == 0) throw new IllegalStateException("Every alias of " + Arrays.toString(annotation.value())
                + " is disabled while its command class is registered for another root; declare one root per command class");
        return Collections.singletonList(Annotations.create(Command.class, "value", remappedValues));
    }

    private boolean registerIfEnabled(ConfiguredCommandAliases commandAliases, Object command) {
        String[] roots = CommandRoots.declaredBy(command.getClass());
        if (!commandAliases.anyEnabled(roots)) return false;
        lamp.register(command);
        commandRoots.registered(roots);
        return true;
    }

    public void dispatchModlConsoleCommand(String commandLine) {
        dispatchAs(lamp, platform.networkConsoleActor(lamp), commandLine);
    }

    @SuppressWarnings("unchecked")
    private static <A extends CommandActor> void dispatchAs(Lamp<A> lamp, CommandActor actor, String commandLine) {
        lamp.dispatch((A) actor, commandLine);
    }

    private Imports createImports(JdbcConnections connections) {
        Path dataFolder = dataDirectory.toAbsolutePath();
        Path pluginsFolder = dataFolder.getParent();
        SourceContext context = SourceContext.builder()
                .pluginsFolder(pluginsFolder != null ? pluginsFolder : dataFolder)
                .serverRoot(Paths.get("").toAbsolutePath())
                .offlineUuids(platform::assignsOfflineUuids)
                .scratch(Imports.scratchFolder(dataFolder))
                .connections(connections)
                .pluginEnabled(platform::isPluginEnabled)
                .clock(System::currentTimeMillis)
                .logger(logger)
                .build();
        return Imports.builder()
                .sources(ImportSourceCatalog.of(context))
                .backend(importBackend)
                .localeManager(localeManager)
                .console(message -> platform.runOnMainThread(() -> platform.log(message)))
                .logger(logger)
                .dataFolder(dataDirectory)
                .serverName(this::importingServerName)
                .build();
    }

    private String importingServerName() {
        String name = platform.getServerName();
        return name == null || name.trim().isEmpty() ? PluginConfiguration.DEFAULT_SERVER_NAME : name;
    }

    private void registerAsyncAliases(ConfiguredCommandAliases commandAliases, String... keys) {
        for (String key : keys) {
            for (String alias : commandAliases.aliasesFor(key)) {
                asyncCommandExecutor.registerAsyncAlias(alias);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyLocaleConfig(Map<String, Object> config, PluginLogger logger) {
        DateFormatter formatter = DateFormatter.defaults();
        try {
            if (config.containsKey("locale_config")) {
                Map<String, Object> localeConfig = (Map<String, Object>) config.get("locale_config");
                this.localeManager.setConfigValues(localeConfig);
                formatter = DateFormatter.configured(stringOrNull(localeConfig.get("date_format")),
                        stringOrNull(localeConfig.get("timezone")), logger);
            }
        } catch (Exception e) {
            logger.warning("Failed to load locale config: " + e.getMessage());
        }
        this.localeManager.applyDateFormat(formatter);
    }

    private static @Nullable String stringOrNull(@Nullable Object value) {
        return value instanceof String ? (String) value : null;
    }

    public synchronized BackendServices connect(BackendSession session, Consumer<BackendServices> wiring) {
        if (connected) throw new IllegalStateException("modl is already connected to its server");
        BackendServices services = BackendServices.create(this, session);
        try {
            wiring.accept(services);
            services.start();
        } catch (RuntimeException | Error failure) {
            services.shutdown();
            throw failure;
        }
        connected = true;
        teardown.push("disconnect from the modl server", services::shutdown);
        teardown.push("stop the import", imports::shutdown);
        imports.resumeInterrupted();
        updateNotice.announceOnce(session.getStartup(), updateCheckerConfig.enabled);
        return services;
    }

    void loadServerState(SyncService syncService) {
        if (punishCommand != null) {
            punishCommand.initializePunishmentTypes();
            syncService.addPunishmentTypesListener(punishCommand::updatePunishmentTypesCache);
        }
        staffPermissionService.reload();
        punishmentTypeCacheManager.initialize(httpClientHolder.getClient(), logger);
        syncService.addPunishmentTypesListener(punishmentTypeCacheManager::update);
    }

    public void shutdown() {
        teardown.unwind();
    }

    private static void shutdownSharedExecutors() {
        MojangProfiles.shutdown();
        PlayerHeadItemBuilder.shutdown();
        Java8Collections.shutdown();
    }

    private void reloadRuntimeConfiguration() {
        configManager.reloadAll();
        cache.setPunishmentTypeItems(configManager.getPunishmentTypeItems());
        Map<String, Object> freshConfig = configManager.getRuntimeConfigSource().root();
        applyChatAndCommandSettings(freshConfig);
    }

    private void applyChatAndCommandSettings(Map<String, Object> config) {
        chatCommandLogService.setCommandRedactor(new CredentialCommandRedactor(
                commandAliases, PluginConfiguration.loadRedactedCommands(config, logger)));
        PluginConfiguration.LoggingConfig logging = PluginConfiguration.loadLoggingConfig(config, logger);
        chatCommandLogService.setPolicy(new ChatCommandLogPolicy(logging.chat, logging.commands,
                commandMatcher(logging.neverLoggedCommands)));
        commandInterceptService.setMutedCommands(commandMatcher(PluginConfiguration.loadMutedCommands(config, logger)));
    }

    private CommandMatcher commandMatcher(Collection<String> commands) {
        return CommandMatcher.of(commands, platform::commandNames);
    }
}

