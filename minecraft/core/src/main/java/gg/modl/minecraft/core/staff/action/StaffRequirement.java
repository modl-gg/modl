package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.command.AdminOnly;
import gg.modl.minecraft.core.command.AnyCoreState;
import gg.modl.minecraft.core.command.LocalConsoleOnly;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.command.RequiresPlayerView;
import gg.modl.minecraft.core.command.Staff2faGated;
import gg.modl.minecraft.core.command.StaffNo2fa;
import gg.modl.minecraft.core.command.StaffOnly;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.list.AnnotationList;

@Value
@Builder
public class StaffRequirement {
    boolean localConsoleOnly;
    boolean anyCoreState;
    boolean playerOnly;
    boolean staffOnly;
    boolean adminOnly;
    boolean playerView;
    @Nullable String permission;
    boolean staff2fa;

    public static StaffRequirement fromAnnotations(AnnotationList annotations) {
        RequiresPermission requiresPermission = annotations.get(RequiresPermission.class);
        boolean staffNo2fa = annotations.contains(StaffNo2fa.class);
        boolean staffOnly = annotations.contains(StaffOnly.class);
        boolean adminOnly = annotations.contains(AdminOnly.class);
        boolean playerView = annotations.contains(RequiresPlayerView.class);
        boolean coveredByStaff2fa = staffOnly || adminOnly || playerView || requiresPermission != null
                || annotations.contains(Staff2faGated.class);
        return StaffRequirement.builder()
                .localConsoleOnly(annotations.contains(LocalConsoleOnly.class))
                .anyCoreState(annotations.contains(AnyCoreState.class))
                .playerOnly(annotations.contains(PlayerOnly.class))
                .staffOnly(staffOnly || staffNo2fa)
                .adminOnly(adminOnly)
                .playerView(playerView)
                .permission(requiresPermission != null ? requiresPermission.value() : null)
                .staff2fa(coveredByStaff2fa && !staffNo2fa)
                .build();
    }
}
