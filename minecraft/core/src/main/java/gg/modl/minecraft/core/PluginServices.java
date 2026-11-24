package gg.modl.minecraft.core;

import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.config.ConfigurationReload;
import gg.modl.minecraft.core.config.GuiConfigSource;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.service.TicketService;
import gg.modl.minecraft.core.impl.menus.util.ChatInputManager;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.punishment.PunishmentActionMessageService;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.service.ReplayService;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.staff.action.TargetActions;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public final class PluginServices implements StaffAudience {
    private static volatile @Nullable PluginServices instance;

    private final Cache cache;
    private final LocaleManager localeManager;
    private final Staff2faService staff2faService;
    private final StaffActionGate staffActionGate;
    private final TargetActions targetActions;
    private final ChatInputManager chatInputManager;
    private final PunishmentMessageService punishmentMessageService;
    private final PunishmentActionMessageService punishmentActionMessageService;
    private final PanelUrl panelUrl;
    private final GuiConfigSource guiConfigs;
    private final ConfigurationReload configurationReload;

    private volatile @Nullable ReplayService replayService;
    private volatile @Nullable TicketService ticketService;

    public static synchronized void install(PluginServices services) {
        instance = services;
    }

    public static synchronized void uninstall(PluginServices services) {
        if (instance == services) instance = null;
    }

    public static Optional<PluginServices> installed() {
        return Optional.ofNullable(instance);
    }

    public static PluginServices get() {
        PluginServices services = instance;
        if (services == null) throw new IllegalStateException("modl is not running");
        return services;
    }

    public static Cache cache() {
        return get().cache;
    }

    public static LocaleManager locale() {
        return get().localeManager;
    }

    public static ChatInputManager chatInput() {
        return get().chatInputManager;
    }

    public static PunishmentMessageService punishmentMessages() {
        return get().punishmentMessageService;
    }

    public static PunishmentActionMessageService punishmentActions() {
        return get().punishmentActionMessageService;
    }

    public static GuiConfigSource guiConfigs() {
        return get().guiConfigs;
    }

    public static ConfigurationReload configurationReload() {
        return get().configurationReload;
    }

    public static @Nullable ReplayService replay() {
        PluginServices services = instance;
        return services == null ? null : services.replayService;
    }

    public static @Nullable TicketService ticket() {
        PluginServices services = instance;
        return services == null ? null : services.ticketService;
    }

    public void setReplayService(@Nullable ReplayService replayService) {
        this.replayService = replayService;
    }

    public void setTicketService(@Nullable TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean includes(UUID uuid) {
        return PermissionUtil.isStaff(uuid, cache)
                && (staff2faService == null || !staff2faService.isEnabled() || staff2faService.isAuthenticated(uuid));
    }
}
