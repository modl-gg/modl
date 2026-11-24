package gg.modl.backend.auth;

import gg.modl.backend.auth.repository.AuthCodeRepository;
import gg.modl.backend.auth.repository.MinecraftLinkCodeRepository;
import gg.modl.backend.auth.repository.PanelLoginTokenRepository;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.PasswordResetTokenRepository;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.repository.WebAuthnChallengeRepository;
import gg.modl.backend.auth.repository.WebAuthnCredentialRepository;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountOffboarding {
    private final PanelSessionRepository sessionRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final WebAuthnChallengeRepository challengeRepository;
    private final AuthCodeRepository authCodeRepository;
    private final PanelLoginTokenRepository panelLoginTokenRepository;
    private final MinecraftLinkCodeRepository minecraftLinkCodeRepository;
    private final StaffCredentialRepository staffCredentialRepository;
    private final StaffTotpRepository staffTotpRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public void revokeAllAccess(Server server, String email) {
        String identity = EmailAddressUtil.normalize(email);
        if (identity == null) {
            return;
        }
        unitOfWork.write(() -> {
            staffCredentialRepository.delete(identity);
            staffTotpRepository.delete(identity);
            revokeSignInState(identity);
        });
    }

    public void transferIdentity(Server server, String fromEmail, String toEmail) {
        String from = EmailAddressUtil.normalize(fromEmail);
        String to = EmailAddressUtil.normalize(toEmail);
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both identities are required to move an account");
        }
        unitOfWork.write(() -> {
            staffCredentialRepository.move(from, to, clock.instant());
            staffTotpRepository.move(from, to);
            revokeSignInState(from);
        });
    }

    public void revokeLoginLinks(Server server, String email) {
        String identity = EmailAddressUtil.normalize(email);
        if (identity != null) {
            unitOfWork.write(() -> panelLoginTokenRepository.deleteForIdentity(identity));
        }
    }

    public void endSessions(Server server, String email) {
        String identity = EmailAddressUtil.normalize(email);
        if (identity != null) {
            sessionRepository.deleteByEmail(identity);
        }
    }

    private void revokeSignInState(String identity) {
        sessionRepository.deleteByEmail(identity);
        credentialRepository.deleteAllByEmail(identity);
        challengeRepository.deleteByEmail(identity);
        authCodeRepository.delete(identity);
        panelLoginTokenRepository.deleteForIdentity(identity);
        minecraftLinkCodeRepository.deleteForIdentity(identity);
        passwordResetTokenRepository.deleteByEmail(identity);
    }
}
