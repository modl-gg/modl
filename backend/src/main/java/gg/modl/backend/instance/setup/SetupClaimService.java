package gg.modl.backend.instance.setup;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SetupClaimService {
    private static final String ALREADY_CLAIMED_MESSAGE = "Setup is already complete; sign in instead";

    private final InstanceContext instanceContext;
    private final InstanceService instanceService;
    private final SetupCodeService setupCodeService;

    public String claim(String code, String email, String publicUrl, SetupClient client) {
        if (instanceContext.instance().isClaimed()) {
            throw alreadyClaimed();
        }
        String ownerEmail = EmailAddressUtil.normalizeIfValid(email);
        if (ownerEmail == null) {
            throw new ValidationException("A valid email address is required");
        }
        HttpOrigin panelOrigin = HttpOrigin.parseExact(publicUrl)
            .orElseThrow(() -> new ValidationException("The panel URL must be an http(s) origin"));
        requireConsumed(setupCodeService.consume(code, client, () -> claimFor(ownerEmail, panelOrigin)));
        return ownerEmail;
    }

    public String signInOwner(String code, SetupClient client) {
        Server server = instanceContext.instance();
        requireOwnerSignIn(server);
        String ownerEmail = EmailAddressUtil.requireNormalized(server.claimedAdminEmail());
        requireConsumed(setupCodeService.consume(code, client, () -> requireOwnerSignIn(instanceService.storedInstance())));
        return ownerEmail;
    }

    private void claimFor(String ownerEmail, HttpOrigin panelOrigin) {
        if (!instanceService.claim(ownerEmail, panelOrigin.value())) {
            throw alreadyClaimed();
        }
    }

    private static void requireOwnerSignIn(Server server) {
        if (SetupPhase.of(server) != SetupPhase.OWNER_SIGN_IN) {
            throw new ConflictException("Owner sign-in with a setup code is not available; sign in with your email instead",
                "SETUP_SIGN_IN_NOT_AVAILABLE");
        }
    }

    private static void requireConsumed(SetupCodeVerdict verdict) {
        switch (verdict) {
            case CONSUMED -> {
            }
            case INVALID -> throw new UnauthorizedException("The setup code is not correct", "SETUP_CODE_INVALID");
            case LOCKED -> throw new UnauthorizedException(
                "Too many wrong codes were entered; restart the server for a new code", "SETUP_CODE_LOCKED");
            case NONE -> throw new UnauthorizedException("The setup code has expired; restart the server for a new code",
                "SETUP_CODE_EXPIRED");
        }
    }

    private static ConflictException alreadyClaimed() {
        return new ConflictException(ALREADY_CLAIMED_MESSAGE, "SETUP_ALREADY_CLAIMED");
    }
}
