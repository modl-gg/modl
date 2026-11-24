package gg.modl.backend.instance.setup;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.server.data.Server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class OwnerSignInReadiness {
    private final SignInSettingsService signInSettings;
    private final AccountFactors accountFactors;

    void require(Server server) {
        if (signInSettings.stored().method() == SignInMethod.PASSWORD
            && !accountFactors.hasPassword(EmailAddressUtil.requireNormalized(server.claimedAdminEmail()))) {
            throw AuthFailures.ownerPasswordRequired();
        }
    }
}
