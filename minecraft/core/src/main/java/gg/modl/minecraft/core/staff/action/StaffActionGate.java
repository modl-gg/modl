package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.util.Permissions;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.command.CommandActor;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

@RequiredArgsConstructor
public final class StaffActionGate {
    private final CoreAvailability availability;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final Staff2faService staff2faService;
    private final Predicate<CommandActor> localConsole;

    public void requireRunning() throws StaffActionDenied {
        CoreState state = availability.state();
        if (!state.isRunning()) throw denied(state.refusalMessageKey());
    }

    public AuthorizedStaff require(StaffActor actor, StaffRequirement requirement) throws StaffActionDenied {
        if (!requirement.isAnyCoreState()) requireRunning();
        if (requirement.isPlayerOnly() && actor.isConsole()) throw denied("general.players_only");
        if (requirement.isLocalConsoleOnly() && !localConsole.test(actor.commandActor())) {
            throw denied("general.local_console_only");
        }
        if (actor.isConsole()) return AuthorizedStaff.console();

        UUID uuid = actor.uuid();
        String staffId = cache.getStaffId(uuid);
        if (requirement.isStaffOnly() && !PermissionUtil.isStaff(uuid, cache)) throw denied("general.no_permission");
        if (actsAsStaff(requirement) && staffId == null) throw denied("general.no_permission");
        if (requirement.isAdminOnly() && !cache.hasPermission(uuid, Permissions.ADMIN)) {
            throw denied("general.no_permission");
        }
        if (requirement.isPlayerView() && !PermissionUtil.canViewPlayers(uuid, cache)) {
            throw denied("general.no_permission");
        }
        String permission = requirement.getPermission();
        if (permission != null && !cache.hasPermission(uuid, permission)) {
            throw denied("general.no_permission");
        }
        if (requirement.isStaff2fa() && staff2faEnforced() && !staff2faService.isAuthenticated(uuid)) {
            throw denied("staff_2fa.not_verified");
        }
        return AuthorizedStaff.player(uuid, staffId);
    }

    public Optional<AuthorizedStaff> authorizeAs(StaffActor actor, StaffRequirement requirement) {
        try {
            return Optional.of(require(actor, requirement));
        } catch (StaffActionDenied denied) {
            actor.reply(denied.getMessage());
            return Optional.empty();
        }
    }

    public boolean authorize(StaffActor actor, StaffRequirement requirement) {
        return authorizeAs(actor, requirement).isPresent();
    }

    public boolean allows(StaffActor actor, StaffRequirement requirement) {
        try {
            require(actor, requirement);
            return true;
        } catch (StaffActionDenied denied) {
            return false;
        }
    }

    private static boolean actsAsStaff(StaffRequirement requirement) {
        return requirement.isStaffOnly() || requirement.isAdminOnly() || requirement.isPlayerView()
                || requirement.getPermission() != null;
    }

    private boolean staff2faEnforced() {
        return staff2faService != null && staff2faService.isEnabled();
    }

    private StaffActionDenied denied(String messageKey) {
        return new StaffActionDenied(localeManager.getMessage(messageKey));
    }
}
