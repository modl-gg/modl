package gg.modl.minecraft.core.impl.menus.inspect;

import dev.simplix.cirrus.menu.DisplayedMenu;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseInspectMenu;
import gg.modl.minecraft.core.impl.menus.util.MenuAsync;
import gg.modl.minecraft.core.impl.menus.util.MenuSlots;
import gg.modl.minecraft.core.impl.menus.util.PunishmentModificationActions;
import gg.modl.minecraft.core.impl.menus.util.PunishmentItemRenderer;
import gg.modl.minecraft.core.impl.menus.util.PunishmentModifyItemFactory;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import org.jspecify.annotations.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

public class ModifyPunishmentMenu extends BaseInspectMenu {
    private final Punishment punishment;
    private final @Nullable Consumer<CirrusPlayerWrapper> menuBackAction, rootBackAction;
    private final PunishmentModificationActions modActions;

    public ModifyPunishmentMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                                 Account targetAccount, Punishment punishment, @Nullable Consumer<CirrusPlayerWrapper> rootBackAction,
                                 @Nullable Consumer<CirrusPlayerWrapper> menuBackAction) {
        super(platform, httpClient, viewerUuid, viewerName, targetAccount, menuBackAction, rootBackAction);
        this.punishment = punishment;
        this.menuBackAction = menuBackAction;
        this.rootBackAction = rootBackAction;
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
    public @Nullable DisplayedMenu display(CirrusPlayerWrapper player) {
        return new StaffViewer(platform, viewerUuid).authorize(StaffRequirements.VIEW_PLAYERS) ? super.display(player) : null;
    }

    @Override
    protected void registerActionHandlers() {
        super.registerActionHandlers();

        modActions.registerHandlers(this::registerActionHandler, chrome, targetAccount, this::reopen);
    }

    private void reopen(CirrusPlayerWrapper player) {
        new ModifyPunishmentMenu(platform, httpClient, viewerUuid, viewerName, targetAccount, punishment,
                rootBackAction, menuBackAction).display(player);
    }

    private void refreshMenu(Click click) {
        MenuAsync.afterSave(platform, viewerUuid, httpClient.getPlayerProfile(targetUuid), response -> {
            if (response.getStatus() != 200) {
                throw new IllegalStateException("The profile refresh answered " + response.getStatus());
            }
            HistoryMenu menu = new HistoryMenu(platform, httpClient, viewerUuid, viewerName,
                    response.getProfile(), menuBackAction);
            MenuAsync.displayWhenLoaded(platform, menu.getDataFuture(), click.player(), menu::display);
        });
    }

}
