package gg.modl.backend.auth.settings;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.auth.staffsecurity.StaffSecurityEntry;
import gg.modl.backend.auth.staffsecurity.StaffSecurityService;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.VersionedSettings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignInSettingsAdministration {
    private final SignInSettingsService signInSettings;
    private final StaffSecurityService staffSecurityService;
    private final AccountFactors accountFactors;
    private final PanelSessionRepository sessionRepository;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;

    public SignInSettingsOverview overview(Server server) {
        return overviewOf(server, signInSettings.state());
    }

    public SignInSettingsOverview update(Server server, AuthSessionData ownerSession, long expectedVersion, SignInSettings requested) {
        VersionedSettings<SignInSettings> saved = unitOfWork.write(() -> {
            SignInSettings current = signInSettings.stored();
            requireOwnerCanUse(ownerSession, current, requested);
            VersionedSettings<SignInSettings> written = signInSettings.replace(expectedVersion, requested);
            if (!current.equals(requested)) {
                panelActionAuditor.recordStaffAction(server, ownerSession.getEmail(), describeChange(current, requested));
            }
            return written;
        });
        return overviewOf(server, saved);
    }

    private void requireOwnerCanUse(AuthSessionData ownerSession, SignInSettings current, SignInSettings requested) {
        String ownerEmail = ownerSession.getEmail();
        if (requested.method() == SignInMethod.PASSWORD && !accountFactors.hasPassword(ownerEmail)) {
            throw AuthFailures.ownerPasswordRequired();
        }
        if (requested.secondFactorRequired() && !current.secondFactorRequired()) {
            boolean sessionVerified = sessionRepository.findByPublicId(ownerSession.publicId())
                .map(PanelSession::secondFactorVerified)
                .orElse(false);
            if (!sessionVerified || !accountFactors.hasSecondFactor(ownerEmail)) {
                throw AuthFailures.ownerSecondFactorRequired();
            }
        }
    }

    private SignInSettingsOverview overviewOf(Server server, VersionedSettings<SignInSettings> state) {
        List<StaffSecurityEntry> entries = staffSecurityService.list(server);
        int withoutPassword = (int) entries.stream().filter(entry -> !entry.hasPassword()).count();
        int withoutSecondFactor = (int) entries.stream().filter(entry -> !entry.hasSecondFactor()).count();
        return new SignInSettingsOverview(state.data(), state.version(), entries.size(), withoutPassword, withoutSecondFactor);
    }

    private static String describeChange(SignInSettings current, SignInSettings requested) {
        return "Changed sign-in settings: method " + current.method() + " to " + requested.method()
            + ", two-factor requirement " + onOff(current.secondFactorRequired()) + " to " + onOff(requested.secondFactorRequired());
    }

    private static String onOff(boolean enabled) {
        return enabled ? "on" : "off";
    }
}
