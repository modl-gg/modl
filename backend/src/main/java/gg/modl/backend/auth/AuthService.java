package gg.modl.backend.auth;

import gg.modl.backend.auth.data.AuthCode;
import gg.modl.backend.auth.repository.AuthCodeRepository;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    private final EmailService emailService;
    private final AuthCodeRepository authCodeRepository;
    private final AuthConfiguration authConfiguration;
    private final OneTimeCodeCodec oneTimeCodeCodec;
    private final LoginLockoutNotice loginLockoutNotice;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public void sendUserLoginCode(Server server, String email) {
        ValidityPeriod validity = codeValidity();
        issueCode(email).ifPresent(code -> emailService.send(
            email,
            EmailHTMLTemplate.USER_CODE.build(server.getServerName(), code, validity),
            "Login code for %s: %s (valid %s)".formatted(EmailAddressUtil.normalize(email), code, validity.describe())));
    }

    public void sendEmailChangeCode(Server server, String newEmail) {
        sendEmailChangeMessage(server, newEmail, EmailHTMLTemplate.EMAIL_CHANGE_CODE, "Email change code");
    }

    public void sendEmailChangeApprovalCode(Server server, String currentEmail) {
        sendEmailChangeMessage(server, currentEmail, EmailHTMLTemplate.EMAIL_CHANGE_APPROVAL_CODE, "Email change approval code");
    }

    private void sendEmailChangeMessage(Server server, String email, EmailHTMLTemplate.CodeTemplate template, String consoleLabel) {
        ValidityPeriod validity = codeValidity();
        issueCode(email).ifPresent(code -> emailService.send(
            email,
            template.build(server.getServerName(), code, validity),
            "%s for %s: %s (valid %s)".formatted(consoleLabel, EmailAddressUtil.normalize(email), code, validity.describe())));
    }

    public boolean verifyCode(Server server, String email, String code) {
        String normalizedEmail = EmailAddressUtil.normalize(email);
        if (normalizedEmail == null) {
            return false;
        }
        Instant now = clock.instant();
        LoginCodeLimits limits = limits();

        String codeHash = oneTimeCodeCodec.hash(code);
        if (unitOfWork.write(() -> consumeOrCountFailure(normalizedEmail, codeHash, now, limits))) {
            return true;
        }
        announceIfLockedOut(normalizedEmail, now, limits);
        return false;
    }

    private boolean consumeOrCountFailure(String normalizedEmail, String codeHash, Instant now, LoginCodeLimits limits) {
        authCodeRepository.lock(normalizedEmail);
        Optional<AuthCode> consumed = authCodeRepository.consumeIfHashMatches(normalizedEmail,
            codeHash, now, limits.failureWindowThreshold(now), limits.failureLimit());
        if (consumed.isPresent()) {
            return true;
        }
        authCodeRepository.recordFailedAttempt(normalizedEmail, now,
            limits.failureWindowThreshold(now), limits.failureWindowEnd(now));
        return false;
    }

    private Optional<String> issueCode(String email) {
        String normalizedEmail = EmailAddressUtil.requireNormalized(email);
        Instant now = clock.instant();
        LoginCodeLimits limits = limits();
        if (announceIfLockedOut(normalizedEmail, now, limits)) {
            return Optional.empty();
        }

        String code = oneTimeCodeCodec.generateNumericCode(authConfiguration.getEmailCodeLength());
        boolean issued = authCodeRepository.issue(normalizedEmail, oneTimeCodeCodec.hash(code), now,
            limits.codeExpiry(now), limits.cooldownThreshold(now));
        if (!issued) {
            log.debug("Skipped issuing a code for {}; the previous code is still in its cooldown", EmailAddressUtil.mask(email));
            return Optional.empty();
        }
        return Optional.of(code);
    }

    private boolean announceIfLockedOut(String normalizedEmail, Instant now, LoginCodeLimits limits) {
        Optional<Instant> lockedUntil = authCodeRepository.find(normalizedEmail)
            .flatMap(stored -> limits.lockedUntil(stored, now));
        lockedUntil.ifPresent(until -> loginLockoutNotice.announce(normalizedEmail, until, now));
        return lockedUntil.isPresent();
    }

    private LoginCodeLimits limits() {
        return LoginCodeLimits.from(authConfiguration);
    }

    private ValidityPeriod codeValidity() {
        return new ValidityPeriod(limits().codeLifetime());
    }
}
