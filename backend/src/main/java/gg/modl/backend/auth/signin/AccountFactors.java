package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.repository.WebAuthnCredentialRepository;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.database.UnitFrame;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountFactors {
    private final StaffCredentialRepository credentialRepository;
    private final StaffTotpRepository totpRepository;
    private final WebAuthnCredentialRepository passkeyRepository;
    private final PanelSessionRepository sessionRepository;
    private final SignInSettingsService signInSettings;

    public boolean hasPassword(String email) {
        return credentialRepository.findPasswordHash(email).isPresent();
    }

    public boolean totpEnabled(String email) {
        return totpRepository.isEnabled(email);
    }

    public int passkeyCount(String email) {
        return passkeyRepository.countByEmail(email);
    }

    public boolean hasSecondFactor(String email) {
        return totpEnabled(email) || passkeyCount(email) > 0;
    }

    public void afterFactorRemoved(String email) {
        UnitFrame.requireWrite();
        if (hasSecondFactor(email)) {
            return;
        }
        if (signInSettings.stored().secondFactorRequired()) {
            throw AuthFailures.lastSecondFactor();
        }
        sessionRepository.clearSecondFactorVerified(email);
    }
}
