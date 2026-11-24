package gg.modl.minecraft.core.impl.menus.inspect;

import dev.simplix.cirrus.menu.CirrusInventoryType;
import dev.simplix.cirrus.menu.DisplayedMenu;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.impl.menus.base.BaseInspectMenu;
import gg.modl.minecraft.core.impl.menus.util.InspectContext;

import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import org.jspecify.annotations.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public class InspectMenu extends BaseInspectMenu {

    public InspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction) {
        this(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, null);
    }

    public InspectMenu(Platform platform, ModlHttpClient httpClient, UUID viewerUuid, String viewerName,
                       Account targetAccount, @Nullable Consumer<CirrusPlayerWrapper> backAction, @Nullable InspectContext inspectContext) {
        super(platform, httpClient, viewerUuid, viewerName, targetAccount, backAction, CirrusInventoryType.GENERIC_9X3, inspectContext);

        title(localeManager().getMessage("menus.titles.inspect", mapOf("player", targetName)));
        buildCompactHeader();
    }

    @Override
    public @Nullable DisplayedMenu display(CirrusPlayerWrapper player) {
        return new StaffViewer(platform, viewerUuid).authorize(StaffRequirements.VIEW_PLAYERS) ? super.display(player) : null;
    }
}
