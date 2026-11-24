package gg.modl.backend.staff.service;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.CreateStaffRequest;
import gg.modl.backend.staff.dto.request.UpdateStaffRequest;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StaffService {
    public static final String STAFF_EMAIL_EXISTS_MESSAGE = "A staff member with this email already exists.";
    private static final String EMAIL_OR_USERNAME_EXISTS_MESSAGE = "Staff member with this email or username already exists";
    private static final String USERNAME_UNAVAILABLE_MESSAGE =
        "Could not reserve a username for the new staff member. Please try again.";
    private static final String DEFAULT_ROLE_NAME = "Helper";
    private static final String PENDING_INVITATION_STATUS = "Pending Invitation";

    private final InvitationRepository invitationRepository;
    private final StaffRepository staffRepository;
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final RankLocks rankLocks;
    private final AccountOffboarding accountOffboarding;
    private final GeneralSettingsService generalSettingsService;
    private final StaffChanges staffChanges;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<StaffResponse> getAllStaff(Server server) {
        return unitOfWork.read(() -> listStaffAndInvitations(server));
    }

    private List<StaffResponse> listStaffAndInvitations(Server server) {
        Instant now = clock.instant();
        List<Staff> staffMembers = staffRepository.findAll();
        List<Invitation> pendingInvitations = invitationRepository.findActive(now);

        Set<String> roleIds = new HashSet<>();
        staffMembers.forEach(staff -> roleIds.add(staff.getRoleId()));
        pendingInvitations.forEach(invitation -> roleIds.add(invitation.getRoleId()));
        roleIds.add(RoleAuthorization.SUPER_ADMIN_ROLE_ID);
        Map<String, String> roleNamesById = permissionService.resolveRoleNames(server, roleIds);

        List<StaffResponse> result = new ArrayList<>();

        String adminEmail = server.getAdminEmail();
        boolean superAdminFound = false;

        for (Staff staff : staffMembers) {
            result.add(StaffResponseFactory.active(staff, fallbackRoleName(roleNamesById, staff.getRoleId())));
            if (RoleAuthorization.isSuperAdminEmail(server, staff.getEmail())) {
                superAdminFound = true;
            }
        }

        if (!superAdminFound && adminEmail != null) {
            result.add(0, StaffResponseFactory.active(SuperAdminStaffSynthesizer.synthesizeFor(server, adminEmail, now),
                fallbackRoleName(roleNamesById, RoleAuthorization.SUPER_ADMIN_ROLE_ID)));
        }

        for (Invitation invitation : pendingInvitations) {
            result.add(new StaffResponse(
                invitation.getId(),
                invitation.getEmail(),
                null,
                fallbackRoleName(roleNamesById, invitation.getRoleId()),
                PENDING_INVITATION_STATUS,
                null,
                null,
                invitation.getCreatedAt()
            ));
        }

        return result;
    }

    public long countStaffIncludingSuperAdmin(Server server) {
        return unitOfWork.read(() -> {
            long staffCount = staffRepository.countAll();
            String adminEmail = server.getAdminEmail();
            boolean ownerHasNoRecord = adminEmail != null && !staffRepository.existsByEmail(adminEmail);
            return ownerHasNoRecord ? staffCount + 1 : staffCount;
        });
    }

    private StaffResponse toStaffResponse(Server server, Staff staff) {
        return StaffResponseFactory.active(staff, permissionService.assignedRoleName(server, staff));
    }

    private @Nullable String roleIdNamed(Server server, String roleName) {
        return permissionService.getRoleByName(server, roleName).map(StaffRole::getId).orElse(null);
    }

    private static String fallbackRoleName(Map<String, String> roleNamesById, @Nullable String roleId) {
        if (roleId == null) {
            return "";
        }
        return roleNamesById.getOrDefault(roleId, roleId);
    }

    public Optional<StaffResponse> getStaffByUsername(Server server, String username) {
        return staffRepository.findByUsername(username).map(staff -> toStaffResponse(server, staff));
    }

    public boolean checkUsernameExists(Server server, String username) {
        return staffRepository.existsByUsername(username);
    }

    public StaffResponse createStaff(Server server, CreateStaffRequest request, RoleAuthorization.PerformerAuthority performer) {
        String email = EmailAddressUtil.normalizeIfValid(request.email());
        if (email == null) {
            throw new ValidationException("A valid email address is required");
        }
        if (RoleAuthorization.isSuperAdminEmail(server, email)) {
            throw new ConflictException(STAFF_EMAIL_EXISTS_MESSAGE);
        }
        if (ReservedStaffNames.isReserved(request.username())) {
            throw new ValidationException(ReservedStaffNames.RESERVED_MESSAGE);
        }
        String requestedRole = request.role() != null ? request.role() : DEFAULT_ROLE_NAME;

        Staff created = unitOfWork.write(() -> {
            rankLocks.lockStaff(server, performer);
            if (staffRepository.existsByEmailOrUsername(email, request.username())) {
                throw new ConflictException(EMAIL_OR_USERNAME_EXISTS_MESSAGE);
            }
            String grantedRoleId = roleIdNamed(server, requestedRole);
            rankLocks.lockRoles(performer, grantedRoleId);
            StaffRole grantedRole = roleAuthorization.assertGrantableRoleId(server, performer, grantedRoleId);
            Staff staff = newStaff(server, email, request.username(), grantedRole.getId());
            if (staffRepository.insert(staff) != StaffInsertOutcome.INSERTED) {
                throw new ConflictException(EMAIL_OR_USERNAME_EXISTS_MESSAGE);
            }
            staffChanges.accessChanged(server, email);
            return staff;
        });
        return toStaffResponse(server, created);
    }

    public Optional<Staff> createInvitedStaff(Server server, String email, String roleId) {
        return unitOfWork.write(() -> {
            String baseUsername = StaffUsernames.fromEmail(email);
            Staff staff = newStaff(server, email, baseUsername, roleId);
            return switch (StaffUsernames.insertWithFreeUsername(staffRepository, staff, baseUsername)) {
                case INSERTED -> {
                    staffChanges.accessChanged(server, email);
                    yield Optional.of(staff);
                }
                case EMAIL_TAKEN -> Optional.<Staff>empty();
                case USERNAME_TAKEN, CONFLICT -> throw new ServiceUnavailableException(USERNAME_UNAVAILABLE_MESSAGE);
            };
        });
    }

    private Staff newStaff(Server server, String email, String username, String roleId) {
        Instant now = clock.instant();
        return Staff.builder()
            .email(email)
            .username(username)
            .roleId(roleId)
            .language(generalSettingsService.getGeneralSettings(server).getDefaultLanguage())
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    public Optional<StaffResponse> updateStaff(Server server, String username, UpdateStaffRequest request) {
        Staff staff = staffRepository.findByUsername(username).orElse(null);

        if (staff == null) {
            return Optional.empty();
        }

        if (request.email() != null) {
            String requestedEmail = EmailAddressUtil.normalizeIfValid(request.email());
            if (requestedEmail == null) {
                throw new ValidationException("A valid email address is required");
            }
            if (!requestedEmail.equals(staff.getEmail())) {
                throw new ForbiddenException("Email changes must be confirmed through email verification in Account Settings");
            }
        }

        return Optional.of(toStaffResponse(server, staff));
    }

    public boolean deleteStaff(Server server, String id, RoleAuthorization.PerformerAuthority performer) {
        return unitOfWork.write(() -> {
            rankLocks.lockStaff(server, performer, id);
            Optional<Invitation> pendingInvitation = invitationRepository.findById(id);
            if (pendingInvitation.isPresent()) {
                return revokeInvitation(server, pendingInvitation.get(), performer);
            }
            return staffRepository.findByIdForUpdate(id)
                .map(staff -> removeStaffMember(server, staff, performer))
                .orElse(false);
        });
    }

    private boolean revokeInvitation(Server server, Invitation invitation, RoleAuthorization.PerformerAuthority performer) {
        rankLocks.lockRoles(performer, invitation.getRoleId());
        roleAuthorization.assertCanRevokeInvitation(server, performer, invitation.getRoleId());
        return invitationRepository.deleteById(invitation.getId());
    }

    private boolean removeStaffMember(Server server, Staff staffToRemove, RoleAuthorization.PerformerAuthority performer) {
        rankLocks.lockRoles(performer, staffToRemove.getRoleId());
        roleAuthorization.assertCanActOnStaff(server, performer, staffToRemove);
        if (!staffRepository.deleteById(staffToRemove.requireId())) {
            return false;
        }
        accountOffboarding.revokeAllAccess(server, staffToRemove.getEmail());
        staffChanges.accessChanged(server, staffToRemove.getEmail());
        return true;
    }

    public Optional<StaffResponse> updateStaffRole(Server server, String id, String newRole, RoleAuthorization.PerformerAuthority performer) {
        Optional<Staff> updated = unitOfWork.write(() -> {
            rankLocks.lockStaff(server, performer, id);
            return staffRepository.findByIdForUpdate(id).flatMap(staff -> changeRole(server, staff, newRole, performer));
        });
        return updated.map(staff -> toStaffResponse(server, staff));
    }

    private Optional<Staff> changeRole(Server server, Staff staff, String newRole, RoleAuthorization.PerformerAuthority performer) {
        String grantedRoleId = roleIdNamed(server, newRole);
        rankLocks.lockRoles(performer, staff.getRoleId(), grantedRoleId);
        roleAuthorization.assertCanActOnStaff(server, performer, staff);
        StaffRole grantedRole = roleAuthorization.assertGrantableRoleId(server, performer, grantedRoleId);
        String staffId = staff.requireId();
        if (!staffRepository.updateRole(staffId, grantedRole.getId(), clock.instant())) {
            return Optional.empty();
        }
        Optional<Staff> stored = staffRepository.findById(staffId);
        stored.ifPresent(changed -> staffChanges.accessChanged(server, changed.getEmail()));
        return stored;
    }

    public void offboardPreviousAdminEmail(Server server, @Nullable String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        unitOfWork.write(() -> {
            Optional<Staff> previousOwner = staffRepository.findByEmail(email);
            boolean remainsStaff = previousOwner
                .filter(staff -> !RoleAuthorization.SUPER_ADMIN_ROLE_ID.equals(staff.getRoleId()))
                .isPresent();
            if (remainsStaff) {
                accountOffboarding.endSessions(server, email);
                staffChanges.profileChanged(server, email);
                return;
            }
            previousOwner.ifPresent(ownerRecord -> {
                staffRepository.deleteById(ownerRecord.requireId());
                staffChanges.accessChanged(server, email);
            });
            accountOffboarding.revokeAllAccess(server, email);
            staffChanges.profileChanged(server, email);
        });
    }
}
