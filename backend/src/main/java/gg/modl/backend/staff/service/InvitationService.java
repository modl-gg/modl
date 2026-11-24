package gg.modl.backend.staff.service;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.password.AccountPasswords;
import gg.modl.backend.auth.password.PreparedPassword;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.InviteStaffRequest;
import gg.modl.backend.staff.dto.response.InviteResultResponse;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationService {
    private final StaffRepository staffRepository;
    private final InvitationRepository invitationRepository;
    private final StaffService staffService;
    private final EmailService emailService;
    private final IdGenerator idGenerator;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final PublicUrls publicUrls;
    private final EmailCapability emailCapability;
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final RankLocks rankLocks;
    private final PanelActionAuditor panelActionAuditor;
    private final StaffCredentialRepository credentialRepository;
    private final AccountPasswords accountPasswords;
    private final SignInSettingsService signInSettings;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    private static final ValidityPeriod INVITATION_VALIDITY = new ValidityPeriod(Duration.ofHours(24));
    private static final String INVALID_EMAIL = "A valid email address is required.";
    private static final String ROLE_NO_LONGER_EXISTS =
        "This invitation references a role that no longer exists. Please request a new invitation.";
    private static final String ROLE_MUST_BE_REISSUED = "This invitation role must be reissued by an administrator";

    public InviteResultResponse sendInvitations(Server server, InviteStaffRequest request, RoleAuthorization.PerformerAuthority performer) {
        List<String> emailsToInvite = new ArrayList<>();
        if (request.emails() != null && !request.emails().isEmpty()) {
            emailsToInvite.addAll(request.emails());
        } else if (request.email() != null) {
            emailsToInvite.add(request.email());
        }

        if (emailsToInvite.isEmpty()) {
            throw new ValidationException("No emails provided");
        }
        if (emailsToInvite.size() > RequestValidationLimits.STAFF_EMAILS_MAX_ENTRIES) {
            throw new ValidationException("At most " + RequestValidationLimits.STAFF_EMAILS_MAX_ENTRIES
                + " invitations can be sent at once");
        }

        List<String> normalizedEmailsToInvite = emailsToInvite.stream()
            .map(EmailAddressUtil::normalize)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        if (normalizedEmailsToInvite.isEmpty()) {
            throw new ValidationException("No valid emails provided");
        }
        StaffRole grantedRole = roleAuthorization.assertGrantableRole(server, performer, request.role());

        List<String> success = new ArrayList<>();
        List<InviteResultResponse.FailedInvite> failed = new ArrayList<>();
        List<InviteResultResponse.InviteLink> inviteLinks = new ArrayList<>();

        for (String email : normalizedEmailsToInvite) {
            if (!EmailAddressUtil.isValid(email)) {
                failed.add(new InviteResultResponse.FailedInvite(email, INVALID_EMAIL));
                continue;
            }
            try {
                processInvitation(server, email, grantedRole, performer, failed).ifPresent(link -> {
                    success.add(email);
                    inviteLinks.add(link);
                });
            } catch (ForbiddenException | ValidationException refused) {
                failed.add(new InviteResultResponse.FailedInvite(email, refused.userMessage()));
            } catch (RuntimeException e) {
                log.error("Error processing invitation for {}", EmailAddressUtil.mask(email), e);
                failed.add(new InviteResultResponse.FailedInvite(email, "Internal server error"));
            }
        }

        String message;
        if (success.isEmpty()) {
            message = "No invitations were sent successfully.";
        } else if (failed.isEmpty()) {
            message = success.size() == 1 ? "Invitation sent successfully." :
                      success.size() + " invitations sent successfully.";
        } else {
            message = success.size() + " invitation(s) sent successfully, " + failed.size() + " failed.";
        }

        return new InviteResultResponse(message, success, failed, visibleInviteLinks(inviteLinks));
    }

    private List<InviteResultResponse.InviteLink> visibleInviteLinks(List<InviteResultResponse.InviteLink> inviteLinks) {
        return emailCapability.isDeliveryEnabled() ? List.of() : List.copyOf(inviteLinks);
    }

    private Optional<InviteResultResponse.InviteLink> processInvitation(Server server, String email, StaffRole role,
                                                                       RoleAuthorization.PerformerAuthority performer,
                                                                       List<InviteResultResponse.FailedInvite> failed) {
        if (RoleAuthorization.isSuperAdminEmail(server, email)) {
            failed.add(new InviteResultResponse.FailedInvite(email, "Cannot send invitation to the admin email address."));
            return Optional.empty();
        }

        String invitationId = EntityIds.next();
        String token = idGenerator.generateToken();
        String tokenHash = oneTimeCodeCodec.hash(token);
        Optional<String> refusal = unitOfWork.write(() -> storeInvitation(server, invitationId, email, role, performer, tokenHash));
        if (refusal.isPresent()) {
            failed.add(new InviteResultResponse.FailedInvite(email, refusal.get()));
            return Optional.empty();
        }

        String invitationLink = publicUrls.invitation(token);

        try {
            emailInvitation(server, email, role, invitationLink);
        } catch (RuntimeException e) {
            log.error("Failed to send invitation email to {}", EmailAddressUtil.mask(email), e);
            unitOfWork.write(() -> invitationRepository.deleteById(invitationId));
            failed.add(new InviteResultResponse.FailedInvite(email, "Failed to send invitation email."));
            return Optional.empty();
        }
        return Optional.of(new InviteResultResponse.InviteLink(email, invitationLink));
    }

    private Optional<String> storeInvitation(Server server, String invitationId, String email, StaffRole role,
                                             RoleAuthorization.PerformerAuthority performer, String tokenHash) {
        rankLocks.lockStaff(server, performer);
        rankLocks.lockRoles(performer, role.getId());
        roleAuthorization.assertGrantableRoleId(server, performer, role.getId());
        if (staffRepository.existsByEmail(email)) {
            return Optional.of("Email is already associated with an existing user.");
        }
        Instant now = clock.instant();
        if (invitationRepository.existsActiveByEmail(email, now)) {
            return Optional.of("An invitation for this email is already pending.");
        }
        invitationRepository.insert(Invitation.builder()
            .id(invitationId)
            .email(email)
            .roleId(role.getId())
            .tokenHash(tokenHash)
            .expiresAt(INVITATION_VALIDITY.expiresAfter(now))
            .createdAt(now)
            .updatedAt(now)
            .build());
        return Optional.empty();
    }

    private void emailInvitation(Server server, String email, StaffRole role, String invitationLink) {
        emailService.sendStaffInviteEmail(email, server.getServerName(), role.getName(), invitationLink, INVITATION_VALIDITY);
    }

    public Optional<InviteResultResponse> resendInvitation(Server server, String invitationId,
                                                           RoleAuthorization.PerformerAuthority performer) {
        Optional<RotatedInvitation> rotation = unitOfWork.write(() -> rotateInvitation(server, invitationId, performer));
        if (rotation.isEmpty()) {
            return Optional.empty();
        }
        RotatedInvitation rotated = rotation.get();
        Invitation previous = rotated.previous();
        String invitationLink = publicUrls.invitation(rotated.token());

        try {
            emailInvitation(server, previous.getEmail(), rotated.role(), invitationLink);
        } catch (RuntimeException e) {
            log.error("Failed to resend invitation email to {}, restoring previous token", EmailAddressUtil.mask(previous.getEmail()), e);
            unitOfWork.write(() -> invitationRepository.rotateToken(invitationId, rotated.tokenHash(), previous.getTokenHash(),
                previous.getExpiresAt(), clock.instant()));
            throw e;
        }

        return Optional.of(new InviteResultResponse(
            "Invitation resent successfully",
            List.of(previous.getEmail()),
            List.of(),
            visibleInviteLinks(List.of(new InviteResultResponse.InviteLink(previous.getEmail(), invitationLink)))
        ));
    }

    private Optional<RotatedInvitation> rotateInvitation(Server server, String invitationId,
                                                         RoleAuthorization.PerformerAuthority performer) {
        return invitationRepository.findById(invitationId).flatMap(invitation -> {
            rankLocks.lockStaff(server, performer);
            rankLocks.lockRoles(performer, invitation.getRoleId());
            StaffRole role = roleAuthorization.assertGrantableRoleId(server, performer, invitation.getRoleId());
            String token = idGenerator.generateToken();
            String tokenHash = oneTimeCodeCodec.hash(token);
            Instant now = clock.instant();
            boolean rotated = invitationRepository.rotateToken(invitationId, invitation.getTokenHash(), tokenHash,
                INVITATION_VALIDITY.expiresAfter(now), now);
            return rotated ? Optional.of(new RotatedInvitation(invitation, role, token, tokenHash)) : Optional.empty();
        });
    }

    public InvitationPreview inspect(Server server, String token) {
        requireWellFormedToken(token);
        Invitation invitation = invitationRepository.findActiveByTokenHash(oneTimeCodeCodec.hash(token), clock.instant())
            .orElseThrow(AuthFailures::invitationInvalid);
        return new InvitationPreview(invitation.getEmail(), signInSettings.current().method());
    }

    public Staff acceptInvitation(Server server, String token, @Nullable String password) {
        requireWellFormedToken(token);
        Optional<PreparedPassword> prepared = passwordFor(server, token, password);
        String tokenHash = oneTimeCodeCodec.hash(token);
        Staff staff = switch (unitOfWork.write(() -> redeem(server, tokenHash, prepared))) {
            case Redemption.Joined joined -> joined.staff();
            case Redemption.Refused refused -> throw refused.reason();
        };
        panelActionAuditor.recordStaffAction(server, staff.getEmail(), "Accepted a staff invitation and signed in");
        return staff;
    }

    private Optional<PreparedPassword> passwordFor(Server server, String token, @Nullable String password) {
        if (signInSettings.current().method() != SignInMethod.PASSWORD) {
            return Optional.empty();
        }
        inspect(server, token);
        return Optional.of(accountPasswords.prepareRequired(password));
    }

    private static void requireWellFormedToken(String token) {
        if (token == null || token.isBlank() || token.length() > RequestValidationLimits.STAFF_INVITATION_TOKEN_MAX_LENGTH) {
            throw AuthFailures.invitationInvalid();
        }
    }

    private Redemption redeem(Server server, String tokenHash, Optional<PreparedPassword> password) {
        Redemption redemption = invitationRepository.consumeActive(tokenHash, clock.instant())
            .map(invitation -> joinStaff(server, invitation))
            .orElseGet(() -> new Redemption.Refused(AuthFailures.invitationInvalid()));
        if (redemption instanceof Redemption.Joined joined) {
            password.ifPresent(prepared -> credentialRepository.replace(joined.staff().getEmail(), prepared.hash(), clock.instant()));
        }
        return redemption;
    }

    private Redemption joinStaff(Server server, Invitation invitation) {
        String email = invitation.getEmail();
        if (RoleAuthorization.isSuperAdminEmail(server, email) || staffRepository.existsByEmail(email)) {
            return emailTaken();
        }
        rankLocks.lockRoles(RoleAuthorization.PerformerAuthority.unidentified(), invitation.getRoleId());
        Optional<StaffRole> role = permissionService.getRoleById(server, invitation.getRoleId());
        if (role.isEmpty()) {
            return new Redemption.Refused(new ValidationException(ROLE_NO_LONGER_EXISTS));
        }
        if (RoleAuthorization.isSuperAdminRole(role.get())) {
            return new Redemption.Refused(new ForbiddenException(ROLE_MUST_BE_REISSUED));
        }
        return staffService.createInvitedStaff(server, email, invitation.getRoleId())
            .<Redemption>map(Redemption.Joined::new)
            .orElseGet(InvitationService::emailTaken);
    }

    private static Redemption emailTaken() {
        return new Redemption.Refused(new ConflictException(StaffService.STAFF_EMAIL_EXISTS_MESSAGE));
    }

    private sealed interface Redemption {
        record Joined(Staff staff) implements Redemption {
        }

        record Refused(RuntimeException reason) implements Redemption {
        }
    }

    private record RotatedInvitation(Invitation previous, StaffRole role, String token, String tokenHash) {
    }
}
