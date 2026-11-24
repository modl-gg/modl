package gg.modl.backend.staff.service;

import static org.mockito.Mockito.mock;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;

final class MinecraftStaffServices {
    private MinecraftStaffServices() {
    }

    static MinecraftStaffService unlocked(StaffRepository staff, PlayerRepository players, PunishmentRepository punishments,
                                          PermissionService permissions, Clock clock) {
        UnitOfWork unitOfWork = new ImmediateUnitOfWork();
        StaffChanges changes = mock(StaffChanges.class);
        MinecraftAccountAssignments assignments = new MinecraftAccountAssignments(staff, new OwnerStaffRecords(staff, clock),
            mock(AccountOffboarding.class), changes, mock(PanelActionAuditor.class), unitOfWork, clock);
        return new MinecraftStaffService(staff, players, punishments, mock(PlayerService.class), permissions,
            mock(RoleAuthorization.class), mock(StaffService.class), mock(RankLocks.class), assignments, unitOfWork, clock);
    }
}
