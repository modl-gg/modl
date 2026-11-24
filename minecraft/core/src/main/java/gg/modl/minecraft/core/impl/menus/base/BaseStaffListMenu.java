package gg.modl.minecraft.core.impl.menus.base;

import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

public abstract class BaseStaffListMenu<T> extends ChromeListMenu<T> {

    protected final boolean isAdmin;
    protected final PanelUrl panelUrl;

    public BaseStaffListMenu(String title, Platform platform, ModlHttpClient httpClient,
                             UUID viewerUuid, String viewerName, boolean isAdmin, PanelUrl panelUrl,
                             @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        super(title, platform, httpClient, viewerUuid, viewerName, backAction,
                new StaffChrome(platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl));
        this.isAdmin = isAdmin;
        this.panelUrl = panelUrl;
    }

}
