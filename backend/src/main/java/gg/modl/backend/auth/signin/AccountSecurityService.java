package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.StaffTotp;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountSecurityService {
    private final AccountFactors accountFactors;
    private final StaffTotpRepository totpRepository;
    private final PanelSessionRepository sessionRepository;
    private final SignInSettingsService signInSettings;
    private final SecondFactorPolicy secondFactorPolicy;

    public AccountSecurity describe(AuthSessionData session) {
        String email = session.getEmail();
        Optional<StaffTotp> totp = totpRepository.find(email).filter(StaffTotp::enabled);
        SignInSettings settings = signInSettings.current();
        boolean verified = sessionRepository.findByPublicId(session.publicId())
            .map(PanelSession::secondFactorVerified)
            .orElse(session.isSecondFactorVerified());
        return new AccountSecurity(
            settings.method(),
            accountFactors.hasPassword(email),
            totp.isPresent(),
            totp.map(StaffTotp::enabledAt).orElse(null),
            totp.isPresent() ? totpRepository.countRecoveryCodes(email) : 0,
            accountFactors.passkeyCount(email),
            settings.secondFactorRequired(),
            verified,
            secondFactorPolicy.state(email, verified));
    }
}
