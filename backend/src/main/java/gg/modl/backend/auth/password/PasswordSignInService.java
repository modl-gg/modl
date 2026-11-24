package gg.modl.backend.auth.password;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.auth.signin.SignInCompletion;
import gg.modl.backend.auth.signin.SignInOutcome;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordSignInService {
    private final SignInSettingsService signInSettings;
    private final PasswordVerification passwordVerification;
    private final StaffCredentialRepository credentialRepository;
    private final PermissionService permissionService;
    private final PasswordHasher passwordHasher;
    private final SignInCompletion signInCompletion;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public SignInOutcome signIn(Server server, String email, String password, SignInClient client) {
        signInSettings.requireMethod(SignInMethod.PASSWORD);
        String identity = EmailAddressUtil.normalize(email);
        String normalizedPassword = PasswordPolicy.normalize(password);
        if (identity == null) {
            throw AuthFailures.invalidCredentials();
        }
        String verifiedHash = passwordVerification.verify(identity, normalizedPassword, client,
            () -> permissionService.isAuthorizedEmail(server, identity), AuthFailures::invalidCredentials);
        String currentHash = rehashIfNeeded(identity, normalizedPassword, verifiedHash);
        return signInCompletion.complete(server, new SignInAttempt(identity, AuthMethod.PASSWORD, currentHash), client);
    }

    private String rehashIfNeeded(String identity, String normalizedPassword, String verifiedHash) {
        if (!passwordHasher.needsRehash(verifiedHash)) {
            return verifiedHash;
        }
        try {
            String upgraded = passwordHasher.hash(normalizedPassword);
            boolean replaced = unitOfWork.write(() -> credentialRepository.updateIfMatches(identity, verifiedHash, upgraded, clock.instant()));
            return replaced ? upgraded : verifiedHash;
        } catch (ServiceUnavailableException busy) {
            log.debug("Skipped upgrading a password hash because hashing is busy");
            return verifiedHash;
        }
    }
}
