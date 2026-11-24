package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.actionhandler.ActionHandlers;
import dev.simplix.cirrus.model.Click;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.staff.OnlinePlayersMenu;
import gg.modl.minecraft.core.impl.menus.staff.RecentPunishmentsMenu;
import gg.modl.minecraft.core.impl.menus.staff.SettingsMenu;
import gg.modl.minecraft.core.impl.menus.staff.StaffReportsMenu;
import gg.modl.minecraft.core.impl.menus.staff.TicketsMenu;

import gg.modl.minecraft.core.staff.action.StaffRequirements;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class StaffNavigationHandlers {
    private StaffNavigationHandlers() {}

    public static void registerAll(
            BiConsumer<String, Consumer<Click>> registrar,
            Platform platform, ModlHttpClient httpClient,
            UUID viewerUuid, String viewerName,
            boolean isAdmin, PanelUrl panelUrl) {

        registrar.accept("openOnlinePlayers", click -> {
            OnlinePlayersMenu menu = new OnlinePlayersMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        });

        registrar.accept("openReports", click -> {
            StaffReportsMenu menu = new StaffReportsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        });

        StaffViewer viewer = new StaffViewer(platform, viewerUuid);
        registrar.accept("openPunishments", viewer.guard(StaffRequirements.VIEW_PLAYERS, click -> {
            RecentPunishmentsMenu menu = new RecentPunishmentsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        }));

        registrar.accept("openTickets", click -> {
            TicketsMenu menu = new TicketsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        });

        registrar.accept("openPanel", click -> {
            click.clickedMenu().close();
            String panelJson = BrowserLink.json(
                    PluginServices.locale().getMessage("menus.staff_tabs.panel_link_label"), panelUrl.get());
            platform.sendJsonMessage(viewerUuid, panelJson);
        });

        registrar.accept("openSettings", click ->
                ActionHandlers.openMenu(new SettingsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null)).handle(click));
    }
}
