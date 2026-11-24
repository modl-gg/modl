package gg.modl.backend.auth.password;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.PanelLoginTokenRepository;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.PasswordResetToken;
import gg.modl.backend.auth.repository.PasswordResetTokenRepository;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    static final Duration LINK_LIFETIME = Duration.ofHours(1);

    private final PasswordResetTokenRepository resetTokenRepository;
    private final StaffCredentialRepository credentialRepository;
    private final PanelSessionRepository sessionRepository;
    private final PanelLoginTokenRepository loginTokenRepository;
    private final SignInSettingsService signInSettings;
    private final PermissionService permissionService;
    private final AccountPasswords accountPasswords;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final IdGenerator idGenerator;
    private final PublicUrls publicUrls;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public PasswordResetLink createFor(Server server, String ownerEmail, Staff target) {
        if (RoleAuthorization.isSuperAdminEmail(server, target.getEmail())) {
            throw AuthFailures.ownerAccountProtected();
        }
        signInSettings.requireMethod(SignInMethod.PASSWORD);
        String token = idGenerator.generateToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LINK_LIFETIME);
        unitOfWork.write(() -> {
            resetTokenRepository.replace(target.getEmail(), oneTimeCodeCodec.hash(token), now, expiresAt);
            panelActionAuditor.recordStaffAction(server, ownerEmail, "Created a password reset link for " + target.getEmail());
        });
        return new PasswordResetLink(publicUrls.passwordReset(token), target.getEmail(), expiresAt);
    }

    public PasswordResetToken inspect(Server server, String token) {
        signInSettings.requireMethod(SignInMethod.PASSWORD);
        return resetTokenRepository.findActive(oneTimeCodeCodec.hash(token), clock.instant())
            .filter(found -> isResettable(server, found.email()))
            .orElseThrow(AuthFailures::resetLinkInvalid);
    }

    public void reset(Server server, String token, String newPassword) {
        inspect(server, token);
        PreparedPassword prepared = accountPasswords.prepare(newPassword);
        String tokenHash = oneTimeCodeCodec.hash(token);
        boolean reset = unitOfWork.write(() -> {
            Instant now = clock.instant();
            return resetTokenRepository.consumeActive(tokenHash, now)
                .filter(consumed -> isResettable(server, consumed.email()))
                .map(consumed -> {
                    credentialRepository.replace(consumed.email(), prepared.hash(), now);
                    sessionRepository.deleteByEmail(consumed.email());
                    loginTokenRepository.deleteForIdentity(consumed.email());
                    panelActionAuditor.recordStaffAction(server, consumed.email(), "Set a new password with a reset link");
                    return true;
                })
                .orElse(false);
        });
        if (!reset) {
            throw AuthFailures.resetLinkInvalid();
        }
    }

    private boolean isResettable(Server server, String email) {
        return !RoleAuthorization.isSuperAdminEmail(server, email) && permissionService.isAuthorizedEmail(server, email);
    }
}
