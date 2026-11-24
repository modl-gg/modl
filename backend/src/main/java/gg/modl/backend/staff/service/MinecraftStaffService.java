package gg.modl.backend.staff.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PlayerDataUtils;
import gg.modl.backend.player.service.StaffIssuedPunishments;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.AssignMinecraftPlayerRequest;
import gg.modl.backend.staff.dto.response.AvailablePlayerResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffPermissionsResponse;
import gg.modl.backend.staff.dto.response.MinecraftStaffSummaryResponse;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftStaffService {
    private static final int AVAILABLE_PLAYERS_LIMIT = 100;
    private static final String UNKNOWN_PLAYER_NAME = "Unknown";

    private final StaffRepository staffRepository;
    private final PlayerRepository playerRepository;
    private final PunishmentRepository punishmentRepository;
    private final PlayerService playerService;
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final StaffService staffService;
    private final RankLocks rankLocks;
    private final MinecraftAccountAssignments assignments;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<MinecraftStaffSummaryResponse> getMinecraftStaffSummary(Server server) {
        List<Staff> allStaff = staffRepository.findAll();

        PlayerStaffData playerStaffData = loadPlayerStaffData(allStaff);
        StaffIssuedPunishments punishmentCounts = StaffIssuedPunishments.of(allStaff, punishmentRepository.countByIssuer(Instant.EPOCH));
        Map<String, StaffRole> rolesById = loadRolesByStaffRoleId(server, allStaff);

        return allStaff.stream()
            .map(staff -> {
                String assignedUuid = staff.getAssignedMinecraftUuid();
                String roleId = RoleAuthorization.effectiveRoleId(server, staff);
                return new MinecraftStaffSummaryResponse(
                    staff.requireId(),
                    staff.getUsername(),
                    staff.getEmail(),
                    roleNameOrFallback(rolesById, roleId),
                    assignedUuid,
                    staff.getAssignedMinecraftUsername(),
                    rolePermissions(rolesById, roleId),
                    staff.getLastSeen(),
                    playerStaffData.playtimeMsFor(assignedUuid),
                    playerStaffData.lastServerFor(assignedUuid),
                    Math.toIntExact(punishmentCounts.issuedBy(staff)),
                    staff.getCreatedAt(),
                    staff.getUpdatedAt()
                );
            })
            .toList();
    }

    private record PlayerStaffData(Map<String, Long> playtimeMap, Map<String, String> lastServerMap) {
        long playtimeMsFor(@Nullable String minecraftUuid) {
            return minecraftUuid == null ? 0L : playtimeMap.getOrDefault(minecraftUuid, 0L);
        }

        @Nullable String lastServerFor(@Nullable String minecraftUuid) {
            return minecraftUuid == null ? null : lastServerMap.get(minecraftUuid);
        }
    }

    private PlayerStaffData loadPlayerStaffData(List<Staff> allStaff) {
        List<String> assignedUuids = allStaff.stream()
            .map(Staff::getAssignedMinecraftUuid)
            .filter(Objects::nonNull)
            .toList();

        if (assignedUuids.isEmpty()) {
            return new PlayerStaffData(Map.of(), Map.of());
        }

        Map<String, Long> playtimeMap = new HashMap<>();
        Map<String, String> lastServerMap = new HashMap<>();

        Instant now = clock.instant();
        for (Player player : playerRepository.findByMinecraftUuids(assignedUuids)) {
            if (player.getMinecraftUuid() == null) {
                continue;
            }

            String uuid = player.getMinecraftUuid().toString();

            playtimeMap.put(uuid, player.data().playtimeSecondsAt(now) * 1000L);

            String lastServer = player.data().lastServer();
            if (lastServer != null) {
                lastServerMap.put(uuid, lastServer);
            }
        }

        return new PlayerStaffData(playtimeMap, lastServerMap);
    }

    private Map<String, StaffRole> loadRolesByStaffRoleId(Server server, List<Staff> staffMembers) {
        List<String> roleIds = staffMembers.stream()
            .map(Staff::getRoleId)
            .toList();
        return permissionService.getRolesByIds(server, roleIds);
    }

    private static String roleNameOrFallback(Map<String, StaffRole> rolesById, @Nullable String roleId) {
        StaffRole role = roleId != null ? rolesById.get(roleId) : null;
        if (role != null) {
            return role.getName();
        }
        return roleId != null ? roleId : "";
    }

    private static List<String> rolePermissions(Map<String, StaffRole> rolesById, @Nullable String roleId) {
        StaffRole role = roleId != null ? rolesById.get(roleId) : null;
        return role != null ? PermissionService.grantedPermissionIds(role) : List.of();
    }

    public List<MinecraftStaffPermissionsResponse> getMinecraftStaffPermissions(Server server) {
        List<Staff> staffWithMinecraft = staffRepository.findAssignedMinecraftStaff();
        Map<String, StaffRole> rolesById = loadRolesByStaffRoleId(server, staffWithMinecraft);
        Function<StaffRole, List<String>> effectivePermissions = roleAuthorization.effectivePermissionsOf(server);

        return staffWithMinecraft.stream()
            .map(staff -> {
                String roleId = RoleAuthorization.effectiveRoleId(server, staff);
                StaffRole role = roleId != null ? rolesById.get(roleId) : null;
                return new MinecraftStaffPermissionsResponse(
                    staff.getAssignedMinecraftUuid(),
                    staff.getAssignedMinecraftUsername() != null ? staff.getAssignedMinecraftUsername() : "",
                    staff.getUsername() != null ? staff.getUsername() : "",
                    staff.requireId(),
                    roleNameOrFallback(rolesById, roleId),
                    role != null ? effectivePermissions.apply(role) : List.of(),
                    staff.getEmail() != null ? staff.getEmail() : ""
                );
            })
            .toList();
    }

    public boolean updateMinecraftStaffRole(Server server, String id, String roleName,
                                            RoleAuthorization.PerformerAuthority performer) {
        roleAuthorization.requireStaffManage(server, performer, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);
        return staffService.updateStaffRole(server, id, roleName, performer).isPresent();
    }

    public boolean markStaffDisconnected(Server server, String minecraftUuid) {
        String uuid = UuidUtils.normalize(minecraftUuid);
        return unitOfWork.write(() -> staffRepository.updateLastSeenByAssignedMinecraftUuid(uuid, clock.instant()));
    }

    public Optional<StaffResponse> assignMinecraftPlayer(Server server, String email,
                                                         AssignMinecraftPlayerRequest request,
                                                         RoleAuthorization.PerformerAuthority performer) {
        roleAuthorization.requireStaffManage(server, performer, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);

        if (findStaffIdentity(server, email) == null) {
            return Optional.empty();
        }
        Player player = isClearing(request) ? null : requirePlayer(server, request);
        Optional<Staff> changed = unitOfWork.write(() -> lockStaffIdentity(server, email, performer).flatMap(target -> {
            rankLocks.lockRoles(performer, target.getRoleId());
            roleAuthorization.assertCanAssignMinecraftPlayer(server, performer, target);
            return player == null
                ? clearAccount(server, target, performer.email())
                : assignPlayer(server, target, player, performer.email());
        }));
        return changed.map(staff -> toStaffResponse(server, staff));
    }

    private static boolean isClearing(AssignMinecraftPlayerRequest request) {
        return (request.minecraftUuid() == null || request.minecraftUuid().isEmpty())
            && (request.minecraftUsername() == null || request.minecraftUsername().isEmpty());
    }

    private Player requirePlayer(Server server, AssignMinecraftPlayerRequest request) {
        String minecraftUuid = request.minecraftUuid();
        String minecraftUsername = request.minecraftUsername();
        Optional<Player> player;
        if (minecraftUuid != null && !minecraftUuid.isEmpty()) {
            player = playerRepository.findByMinecraftUuid(UuidUtils.normalize(minecraftUuid), PlayerParts.USERNAMES);
        } else if (minecraftUsername != null) {
            player = playerService.findBestByUsername(server, minecraftUsername, PlayerParts.USERNAMES);
        } else {
            player = Optional.empty();
        }
        return player.orElseThrow(() -> new ResourceNotFoundException("Minecraft player not found"));
    }

    private Optional<Staff> lockStaffIdentity(Server server, String email, RoleAuthorization.PerformerAuthority performer) {
        return Optional.ofNullable(findStaffIdentity(server, email)).flatMap(found -> {
            rankLocks.lockStaff(server, performer, found.getId());
            return locked(found);
        });
    }

    private Optional<Staff> lockOwnIdentity(Server server, String email) {
        return Optional.ofNullable(findStaffIdentity(server, email)).flatMap(this::locked);
    }

    private Optional<Staff> locked(Staff found) {
        return found.getId() == null ? Optional.of(found) : staffRepository.findByIdForUpdate(found.getId());
    }

    private Optional<Staff> assignPlayer(Server server, Staff staff, Player player, @Nullable String actorEmail) {
        String minecraftUuid = player.getMinecraftUuid().toString();
        conflictingAssignee(minecraftUuid, staff).ifPresent(holder -> {
            throw new ConflictException("This Minecraft player is already assigned to " + holder.getUsername());
        });
        return assignments.store(server, staff, minecraftUuid, PlayerDataUtils.extractLatestUsername(player.getUsernames()),
            actorEmail);
    }

    public MinecraftLinkOutcome linkOwnMinecraftAccount(Server server, String email, String minecraftUuid, String minecraftUsername) {
        String normalizedUuid = UuidUtils.normalize(minecraftUuid);
        return unitOfWork.write(() -> {
            Staff staff = lockOwnIdentity(server, email).orElse(null);
            if (staff == null) {
                return MinecraftLinkOutcome.IDENTITY_NOT_FOUND;
            }
            if (conflictingAssignee(normalizedUuid, staff).isPresent()) {
                return MinecraftLinkOutcome.ASSIGNED_TO_OTHER;
            }
            return assignments.store(server, staff, normalizedUuid, minecraftUsername, staff.getEmail()).isPresent()
                ? MinecraftLinkOutcome.LINKED
                : MinecraftLinkOutcome.IDENTITY_NOT_FOUND;
        });
    }

    public void unlinkOwnMinecraftAccount(Server server, String email) {
        unitOfWork.write(() -> {
            lockOwnIdentity(server, email).ifPresent(staff -> clearAccount(server, staff, staff.getEmail()));
        });
    }

    public Optional<Staff> findMinecraftAssignee(Server server, String minecraftUuid) {
        return staffRepository.findByAssignedMinecraftUuid(UuidUtils.normalize(minecraftUuid));
    }

    public boolean isAssignedToAnotherIdentity(Server server, String email, String minecraftUuid) {
        Staff staff = findStaffIdentity(server, email);
        return staff != null && conflictingAssignee(UuidUtils.normalize(minecraftUuid), staff).isPresent();
    }

    public boolean isAssignedTo(Server server, String email, String minecraftUuid) {
        Staff staff = findStaffIdentity(server, email);
        return staff != null && staff.getId() != null && findMinecraftAssignee(server, minecraftUuid)
            .map(Staff::getId)
            .filter(staff.getId()::equals)
            .isPresent();
    }

    @Nullable
    private Staff findStaffIdentity(Server server, String email) {
        return staffRepository.findByEmail(email)
            .orElseGet(() -> SuperAdminStaffSynthesizer.synthesizeIfAdminEmail(server, email, clock.instant()));
    }

    private Optional<Staff> conflictingAssignee(String minecraftUuid, Staff staff) {
        return staffRepository.findByAssignedMinecraftUuidExcludingId(minecraftUuid, staff.getId());
    }

    private Optional<Staff> clearAccount(Server server, Staff staff, @Nullable String actorEmail) {
        return staff.getId() == null ? Optional.of(staff) : assignments.store(server, staff, null, null, actorEmail);
    }

    public List<AvailablePlayerResponse> getAvailablePlayers(Server server) {
        return playerRepository.findAvailableForStaffAssignment(AVAILABLE_PLAYERS_LIMIT).stream()
            .map(player -> new AvailablePlayerResponse(
                player.minecraftUuid(),
                Objects.requireNonNullElse(player.username(), UNKNOWN_PLAYER_NAME)
            ))
            .toList();
    }

    private StaffResponse toStaffResponse(Server server, Staff staff) {
        return StaffResponseFactory.active(staff, permissionService.assignedRoleName(server, staff));
    }
}
