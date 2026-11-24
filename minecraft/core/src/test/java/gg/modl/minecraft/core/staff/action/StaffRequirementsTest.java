package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.PunishmentTypeClassifier;
import gg.modl.minecraft.core.command.RequiresPermission;
import gg.modl.minecraft.core.impl.commands.staff.punishments.BanCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.BlacklistCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.KickCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.MuteCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.PardonCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.UnbanCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.UnmuteCommand;
import gg.modl.minecraft.core.impl.commands.staff.punishments.WarnCommand;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaffRequirementsTest {
    @Test
    void applyPermissionsAreKeyedByTypeOrdinal() {
        assertEquals("punishment.apply.7", StaffRequirements.applyPunishment(7).getPermission());
        assertEquals("punishment.apply." + PunishmentTypeClassifier.ORDINAL_BAN, permissionOf(BanCommand.class));
        assertEquals("punishment.apply." + PunishmentTypeClassifier.ORDINAL_MUTE, permissionOf(MuteCommand.class));
        assertEquals("punishment.apply." + PunishmentTypeClassifier.ORDINAL_KICK, permissionOf(KickCommand.class));
        assertEquals("punishment.apply." + PunishmentTypeClassifier.ORDINAL_BLACKLIST,
                permissionOf(BlacklistCommand.class));
    }

    @Test
    void everyPardonRootRequiresThePardonSubPermission() {
        assertEquals(Permissions.PUNISHMENT_MODIFY_PARDON, permissionOf(PardonCommand.class));
        assertEquals(Permissions.PUNISHMENT_MODIFY_PARDON, permissionOf(UnbanCommand.class));
        assertEquals(Permissions.PUNISHMENT_MODIFY_PARDON, permissionOf(UnmuteCommand.class));
    }

    @Test
    void warningHasItsOwnPermission() {
        assertEquals(Permissions.PLAYER_WARN, permissionOf(WarnCommand.class));
        assertEquals(Permissions.PLAYER_NOTE, StaffRequirements.ADD_PLAYER_NOTE.getPermission());
    }

    private static String permissionOf(Class<?> command) {
        for (Method method : command.getDeclaredMethods()) {
            RequiresPermission permission = method.getAnnotation(RequiresPermission.class);
            if (permission != null) return permission.value();
        }
        throw new AssertionError(command.getSimpleName() + " declares no permission");
    }
}
