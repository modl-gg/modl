package gg.modl.minecraft.core.staff.action;

import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.core.AsyncCommandExecutor;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.impl.menus.staff.StaffMenu;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@RequiredArgsConstructor
public final class StaffMenuActions {
    public static final StaffRequirement REQUIREMENT = StaffRequirement.builder()
            .playerOnly(true)
            .staffOnly(true)
            .staff2fa(true)
            .build();

    private final StaffActionContext context;
    private final AsyncCommandExecutor commandExecutor;
    private final HttpClientHolder httpClientHolder;
    private final PanelUrl panelUrl;

    public void openStaffMenu(StaffActor actor) {
        Platform platform = context.getPlatform();
        if (!platform.areMenusAvailable()) {
            context.reply(actor, "general.gui_unavailable");
            return;
        }
        UUID viewer = actor.uuid();
        boolean isAdmin = context.getCache().hasPermission(viewer, Permissions.ADMIN);
        String viewerName = CommandUtil.resolveSenderName(viewer, context.getCache(), platform);
        commandExecutor.execute(() -> {
            CirrusPlayerWrapper player = platform.getPlayerWrapper(viewer);
            if (player == null) return;
            new StaffMenu(platform, httpClientHolder.getClient(), viewer, viewerName, isAdmin, panelUrl, null)
                    .display(player);
        });
    }
}
