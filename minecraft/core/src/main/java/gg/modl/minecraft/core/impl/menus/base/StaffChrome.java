package gg.modl.minecraft.core.impl.menus.base;

import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.item.CirrusItem;
import dev.simplix.cirrus.model.Click;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.util.StaffNavigationHandlers;
import gg.modl.minecraft.core.impl.menus.util.StaffTabItems;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.staff.action.StaffMenuActions;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class StaffChrome implements MenuChrome {

    private final Platform platform;
    private final ModlHttpClient httpClient;
    private final UUID viewerUuid;
    private final String viewerName;
    private final boolean isAdmin;
    private final PanelUrl panelUrl;

    public StaffChrome(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       boolean isAdmin, PanelUrl panelUrl) {
        this.platform = platform;
        this.httpClient = httpClient;
        this.viewerUuid = viewerUuid;
        this.viewerName = viewerName;
        this.isAdmin = isAdmin;
        this.panelUrl = panelUrl;
    }

    @Override
    public Map<Integer, CirrusItem> headerItems(boolean compact) {
        return StaffTabItems.create(compact, new StaffViewer(platform, viewerUuid)::allows);
    }

    @Override
    public void registerNavigation(BiConsumer<String, Consumer<Click>> registrar) {
        StaffViewer viewer = new StaffViewer(platform, viewerUuid);
        StaffNavigationHandlers.registerAll(
                (name, handler) -> registrar.accept(name, viewer.guard(StaffMenuActions.REQUIREMENT, handler)), platform, httpClient, viewerUuid, viewerName, isAdmin, panelUrl);
    }
}
