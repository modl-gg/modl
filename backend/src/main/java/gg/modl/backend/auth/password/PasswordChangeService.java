package gg.modl.backend.auth.password;

import gg.modl.backend.auth.AccountAuthorization;
import gg.modl.backend.auth.repository.PanelLoginTokenRepository;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.PasswordResetTokenRepository;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.CredentialProof;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.signin.SensitiveOperation;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordChangeService {
    private final StaffCredentialRepository credentialRepository;
    private final PanelSessionRepository sessionRepository;
    private final PanelLoginTokenRepository loginTokenRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final AccountPasswords accountPasswords;
    private final Reauthentication reauthentication;
    private final AccountAuthorization accountAuthorization;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public void setOwnPassword(Server server, AuthSessionData session, String newPassword, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        Optional<String> currentHash = credentialRepository.findPasswordHash(email);
        SensitiveOperation operation = currentHash.isPresent() ? SensitiveOperation.CHANGE_PASSWORD : SensitiveOperation.ADD_CREDENTIAL;
        PasswordPolicy.requireAcceptable(newPassword);
        reauthentication.require(session, operation, proof, client);
        PreparedPassword prepared = accountPasswords.prepare(newPassword);
        unitOfWork.write(() -> {
            accountAuthorization.requireAuthorizedForUpdate(server, email);
            Instant now = clock.instant();
            boolean stored = currentHash
                .map(expected -> credentialRepository.updateIfMatches(email, expected, prepared.hash(), now))
                .orElseGet(() -> credentialRepository.insertIfAbsent(email, prepared.hash(), now));
            if (!stored) {
                throw new ConflictException("The password was changed by another request; reload and try again");
            }
            sessionRepository.deleteByEmailExcept(email, session.publicId());
            loginTokenRepository.deleteForIdentity(email);
            resetTokenRepository.deleteByEmail(email);
            panelActionAuditor.recordStaffAction(server, email, currentHash.isPresent() ? "Changed their password" : "Set a password");
        });
    }
}
