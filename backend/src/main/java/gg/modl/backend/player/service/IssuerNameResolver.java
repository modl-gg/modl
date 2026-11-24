package gg.modl.backend.player.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.SuperAdminStaffSynthesizer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IssuerNameResolver {
    private static final String CONSOLE = "Console";
    private static final String UNKNOWN_STAFF = "Unknown Staff";

    private final StaffRepository staffRepository;

    public PunishmentIssuer panelIssuer(Server server, @Nullable String sessionEmail) {
        String email = EmailAddressUtil.normalize(sessionEmail);
        if (email == null) {
            throw new ForbiddenException("No authenticated user found for this action");
        }
        return staffRepository.findByEmail(email)
            .map(staff -> PunishmentIssuer.staff(staff.getUsername(), staff.getId()))
            .orElseGet(() -> ownerWithoutStaffRecord(server, email));
    }

    private static PunishmentIssuer ownerWithoutStaffRecord(Server server, String email) {
        if (!RoleAuthorization.isSuperAdminEmail(server, email)) {
            throw new ForbiddenException("Only staff members can perform this action");
        }
        return PunishmentIssuer.staff(SuperAdminStaffSynthesizer.SUPER_ADMIN_USERNAME, null);
    }

    public String resolve(@Nullable String issuerId, @Nullable String issuerName, Server server) {
        if (issuerId != null) {
            String username = batchResolve(Set.of(issuerId), server).get(issuerId);
            if (username != null) {
                return username;
            }
            return issuerName != null ? issuerName : UNKNOWN_STAFF;
        }
        return issuerName != null ? issuerName : CONSOLE;
    }

    public Map<String, String> batchResolve(Set<String> issuerIds, Server server) {
        if (issuerIds == null || issuerIds.isEmpty()) {
            return Map.of();
        }
        return staffRepository.findUsernamesByIds(issuerIds);
    }

    public Map<String, String> resolveForPunishments(Server server, Collection<Punishment> punishments) {
        Set<String> ids = new HashSet<>();
        for (Punishment punishment : punishments) {
            ids.addAll(PunishmentQueryService.collectIssuerIds(punishment));
        }
        return batchResolve(ids, server);
    }

    public Map<String, String> resolveForPlayers(Server server, Collection<Player> players) {
        Set<String> ids = new HashSet<>();
        for (Player player : players) {
            List<Punishment> punishments = player.getPunishments();
            if (punishments == null) {
                continue;
            }
            for (Punishment punishment : punishments) {
                ids.addAll(PunishmentQueryService.collectIssuerIds(punishment));
            }
        }
        return batchResolve(ids, server);
    }
}
