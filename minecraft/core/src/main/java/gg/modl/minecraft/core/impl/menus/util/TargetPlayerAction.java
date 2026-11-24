package gg.modl.minecraft.core.impl.menus.util;

import dev.simplix.cirrus.model.Click;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.staff.action.StaffActor;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.TargetActions;

import java.util.Optional;
import java.util.UUID;

public final class TargetPlayerAction {
    private TargetPlayerAction() {}

    public static void handle(Click click, Platform platform, UUID viewerUuid, UUID targetUuid) {
        Optional<PluginServices> installed = PluginServices.installed();
        if (!installed.isPresent()) return;
        PluginServices services = installed.get();
        StaffActor viewer = StaffActors.player(viewerUuid, platform);
        if (!services.getStaffActionGate().authorize(viewer, TargetActions.REQUIREMENT)) return;
        AbstractPlayer target = platform.getPlayer(targetUuid);
        if (target == null) {
            viewer.reply(services.getLocaleManager().getMessage("target.not_online"));
            return;
        }
        click.clickedMenu().close();
        services.getTargetActions().select(viewer, target);
    }
}
