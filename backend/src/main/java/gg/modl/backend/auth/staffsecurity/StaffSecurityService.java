package gg.modl.backend.auth.staffsecurity;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.password.PasswordResetLink;
import gg.modl.backend.auth.password.PasswordResetService;
import gg.modl.backend.auth.repository.PanelLoginTokenRepository;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.repository.WebAuthnChallengeRepository;
import gg.modl.backend.auth.repository.WebAuthnCredentialRepository;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StaffSecurityService {
    private final StaffRepository staffRepository;
    private final StaffCredentialRepository credentialRepository;
    private final StaffTotpRepository totpRepository;
    private final WebAuthnCredentialRepository passkeyRepository;
    private final WebAuthnChallengeRepository passkeyChallengeRepository;
    private final PanelSessionRepository sessionRepository;
    private final PanelLoginTokenRepository loginTokenRepository;
    private final PasswordResetService passwordResetService;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;

    public List<StaffSecurityEntry> list(Server server) {
        Set<String> withPassword = credentialRepository.findEmails();
        Set<String> withTotp = totpRepository.findEnabledEmails();
        Set<String> withPasskey = passkeyRepository.findEmails();
        String ownerEmail = EmailAddressUtil.normalize(server.getAdminEmail());
        List<StaffSecurityEntry> entries = new ArrayList<>();
        boolean ownerListed = false;
        for (Staff staff : staffRepository.findAll()) {
            String email = staff.getEmail();
            boolean owner = email.equals(ownerEmail);
            ownerListed |= owner;
            entries.add(entry(staff.getId(), email, owner, withPassword, withTotp, withPasskey));
        }
        if (ownerEmail != null && !ownerListed) {
            entries.addFirst(entry(null, ownerEmail, true, withPassword, withTotp, withPasskey));
        }
        return List.copyOf(entries);
    }

    public PasswordResetLink createPasswordResetLink(Server server, String ownerEmail, String staffId) {
        return passwordResetService.createFor(server, ownerEmail, requireResettableStaff(server, staffId));
    }

    public void resetSecondFactor(Server server, String ownerEmail, String staffId) {
        String email = requireResettableStaff(server, staffId).getEmail();
        unitOfWork.write(() -> {
            totpRepository.delete(email);
            passkeyRepository.deleteAllByEmail(email);
            passkeyChallengeRepository.deleteByEmail(email);
            sessionRepository.deleteByEmail(email);
            loginTokenRepository.deleteForIdentity(email);
            panelActionAuditor.recordStaffAction(server, ownerEmail, "Reset two-factor authentication for " + email);
        });
    }

    private Staff requireResettableStaff(Server server, String staffId) {
        Staff staff = staffRepository.findById(staffId).orElseThrow(AuthFailures::staffNotFound);
        if (RoleAuthorization.isSuperAdminEmail(server, staff.getEmail())) {
            throw AuthFailures.ownerAccountProtected();
        }
        return staff;
    }

    private static StaffSecurityEntry entry(@Nullable String staffId, String email, boolean owner, Set<String> withPassword, Set<String> withTotp,
                                            Set<String> withPasskey) {
        return new StaffSecurityEntry(staffId, email, withPassword.contains(email), withTotp.contains(email), withPasskey.contains(email),
            owner);
    }
}
