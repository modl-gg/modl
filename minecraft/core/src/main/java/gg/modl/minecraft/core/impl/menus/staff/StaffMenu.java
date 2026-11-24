package gg.modl.minecraft.core.impl.menus.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseStaffMenu;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

public class StaffMenu extends BaseStaffMenu {

    public StaffMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                     boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl, backAction, CirrusInventoryType.GENERIC_9X3);

        title(localeManager().getMessage("menus.titles.staff_panel"));
        buildCompactHeader();
    }
}
