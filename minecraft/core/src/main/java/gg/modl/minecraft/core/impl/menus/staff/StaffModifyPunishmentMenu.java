package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.RecentPunishmentsResponse;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.PunishmentModificationActions;
import gg.modl.minecraft.core.impl.menus.util.PunishmentItemRenderer;
import gg.modl.minecraft.core.impl.menus.util.PunishmentModifyItemFactory;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class StaffModifyPunishmentMenu extends BaseStaffMenu {
    private final Account targetAccount;
    private final Punishment punishment;
    private final Consumer<CirrusPlayerWrapper> menuBackAction;
    private final PunishmentModificationActions modActions;

    public StaffModifyPunishmentMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                      Account targetAccount, Punishment punishment, boolean isAdmin, PanelUrl panelUrl,
                                      Consumer<CirrusPlayerWrapper> menuBackAction) {
        super(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, menuBackAction);
        this.targetAccount = targetAccount;
        this.punishment = punishment;
        this.menuBackAction = menuBackAction;
        this.modActions = new PunishmentModificationActions(platform, httpClient, viewerUuid, viewerName,
                targetAccount.getMinecraftUuid(), punishment, this::sendMessage, this::refreshMenu, this::display);

        title(localeManager().getMessage("menus.titles.modify_punishment"));
        buildMenu();
    }

    private void buildMenu() {
        buildHeader();

        set(PunishmentItemRenderer.renderSubject(punishment).slot(MenuSlots.MODIFY_SUBJECT));
        StaffViewer viewer = new StaffViewer(platform, viewerUuid);
        new PunishmentModifyItemFactory(punishment).itemsFor(viewer::allows).forEach(this::set);
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        modActions.registerHandlers(this::registerActionHandler, chrome, targetAccount, this::reopen);
    }

    private void reopen(CirrusPlayerWrapper player) {
        new StaffModifyPunishmentMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, punishment, isAdmin,
                panelUrl, menuBackAction).display(player);
    }

    private void refreshMenu(Click click) {
        httpClient.getRecentPunishments(48).thenAccept(response -> {
            if (response.isSuccess() && response.getPunishments() != null) {
                List<RecentPunishmentsMenu.PunishmentWithPlayer> freshData = new ArrayList<>();
                for (RecentPunishmentsResponse.RecentPunishment p : response.getPunishments())
                    freshData.add(RecentPunishmentsMenu.PunishmentWithPlayer.from(p));
                RecentPunishmentsMenu menu = new RecentPunishmentsMenu(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, null, freshData);
                MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
            } else {
                menuBackAction.accept(click.player());
            }
        }).exceptionally(e -> {
            menuBackAction.accept(click.player());
            return null;
        });
    }

}
