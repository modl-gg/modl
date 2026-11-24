package gg.modl.backend.role.service;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RankLocks {
    private static final String AUTHORITY_CHANGED_MESSAGE = "Your role changed while this request ran. Please try again.";

    private final StaffRepository staffRepository;
    private final StaffRoleRepository staffRoleRepository;

    public void lockStaff(Server server, PerformerAuthority performer, @Nullable String... targetStaffIds) {
        Optional<String> rankedEmail = performer.rankedEmail();
        Optional<String> performerId = rankedEmail.flatMap(staffRepository::findByEmail).map(Staff::getId);

        Map<String, Staff> lockedById = new HashMap<>();
        Stream.concat(performerId.stream(), Stream.of(targetStaffIds))
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .forEachOrdered(id -> staffRepository.findByIdForUpdate(id).ifPresent(staff -> lockedById.put(id, staff)));

        if (rankedEmail.isPresent()) {
            String currentRoleId = performerId.map(lockedById::get)
                .map(staff -> RoleAuthorization.effectiveRoleId(server, staff))
                .orElse(null);
            if (!Objects.equals(currentRoleId, performer.roleId())) {
                throw new ForbiddenException(AUTHORITY_CHANGED_MESSAGE);
            }
        }
    }

    public void lockRoles(PerformerAuthority performer, @Nullable String... targetRoleIds) {
        Stream.concat(Stream.ofNullable(performer.roleId()), Stream.of(targetRoleIds))
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .forEachOrdered(staffRoleRepository::findByIdForUpdate);
    }
}
