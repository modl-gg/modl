package gg.modl.backend.auth;

import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.signin.CredentialProof;
import gg.modl.backend.auth.signin.EmailChangeProof;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffProfileService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailChangeService {
    private final PermissionService permissionService;
    private final AuthService authService;
    private final StaffProfileService staffProfileService;
    private final InstanceService instanceService;
    private final InstanceProperties instanceProperties;
    private final AccountOffboarding accountOffboarding;
    private final SessionService sessionService;
    private final EmailService emailService;
    private final StaffTotpRepository totpRepository;
    private final Reauthentication reauthentication;
    private final UnitOfWork unitOfWork;

    public void sendChangeCode(Server server, String currentEmail, String newEmail) {
        boolean isSuperAdmin = permissionService.isSuperAdmin(server, currentEmail);
        String normalizedNewEmail = validateTarget(server, currentEmail, newEmail, isSuperAdmin);

        authService.sendEmailChangeCode(server, normalizedNewEmail);
        if (reauthentication.emailChangeProof(currentEmail) == EmailChangeProof.CURRENT_ADDRESS_CODE) {
            authService.sendEmailChangeApprovalCode(server, currentEmail);
        }
    }

    public IssuedSession changeEmail(Server server, AuthSessionData session, String newEmail, String code, CredentialProof proof,
                                     SignInClient client) {
        String currentEmail = session.getEmail();
        boolean isSuperAdmin = permissionService.isSuperAdmin(server, currentEmail);
        String normalizedNewEmail = validateTarget(server, currentEmail, newEmail, isSuperAdmin);
        reauthentication.requireForEmailChange(server, session, proof, client);

        if (!authService.verifyCode(server, normalizedNewEmail, code)) {
            throw new ValidationException("Invalid or expired verification code.");
        }

        IssuedSession newSession = unitOfWork.write(
            () -> moveAccount(server, session, normalizedNewEmail, isSuperAdmin, client));

        notifyPreviousAddress(server, currentEmail, normalizedNewEmail);
        return newSession;
    }

    private IssuedSession moveAccount(Server server, AuthSessionData session, String newEmail, boolean isSuperAdmin,
                                      SignInClient client) {
        String currentEmail = session.getEmail();
        Optional<Staff> staff = staffProfileService.applyStaffEmailChange(server, currentEmail, newEmail);
        if (staff.isEmpty() && !isSuperAdmin) {
            throw new ResourceNotFoundException("Staff member not found");
        }
        accountOffboarding.transferIdentity(server, currentEmail, newEmail);
        boolean verified = session.isSecondFactorVerified() && totpRepository.isEnabled(newEmail);
        IssuedSession newSession = sessionService.createSession(server, newEmail, session.getAuthMethod(), verified,
            client.ipAddress(), client.userAgent());
        if (isSuperAdmin) {
            instanceService.changeAdminEmail(newEmail);
        }
        return newSession;
    }

    private String validateTarget(Server server, String currentEmail, String newEmail, boolean isSuperAdmin) {
        if (isSuperAdmin && instanceProperties.configuredAdminEmail().isPresent()) {
            throw new ConflictException("The owner email is set by MODL_ADMIN_EMAIL and cannot be changed from the panel");
        }
        String normalizedNewEmail = EmailAddressUtil.normalizeIfValid(newEmail);
        if (normalizedNewEmail == null) {
            throw new ValidationException("A valid email address is required");
        }
        if (normalizedNewEmail.equals(EmailAddressUtil.normalize(currentEmail))) {
            throw new ValidationException("New email must be different from your current email.");
        }
        boolean collidesWithServerAdmin = RoleAuthorization.isSuperAdminEmail(server, normalizedNewEmail);
        if (collidesWithServerAdmin || staffProfileService.isStaffEmailInUse(server, normalizedNewEmail, currentEmail)) {
            throw new ConflictException("Email address already in use");
        }
        return normalizedNewEmail;
    }

    private void notifyPreviousAddress(Server server, String previousEmail, String newEmail) {
        try {
            emailService.send(previousEmail,
                EmailHTMLTemplate.EMAIL_CHANGED_NOTICE.build(server.getServerName(), EmailAddressUtil.mask(newEmail)));
        } catch (Exception e) {
            log.warn("Failed to send email-change notice to the previous address", e);
        }
    }
}
