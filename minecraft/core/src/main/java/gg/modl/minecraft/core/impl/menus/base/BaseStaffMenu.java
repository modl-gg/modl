package gg.modl.minecraft.core.impl.menus.base;

import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

public abstract class BaseStaffMenu extends ChromeMenu {

    protected final boolean isAdmin;
    protected final PanelUrl panelUrl;

    public BaseStaffMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                         boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(platform, httpClient, viewerUuid, viewerName, backAction,
                new StaffChrome(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl));
        this.isAdmin = isAdmin;
        this.panelUrl = panelUrl;
    }

    public BaseStaffMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                         boolean isAdmin, PanelUrl panelUrl, @Nullable Consumer<CirrusPlayerWrapper> backAction, CirrusInventoryType inventoryType) {
        super(platform, httpClient, viewerUuid, viewerName, backAction,
                new StaffChrome(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl), inventoryType);
        this.isAdmin = isAdmin;
        this.panelUrl = panelUrl;
    }

}
