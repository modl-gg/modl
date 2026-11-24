package gg.modl.backend.ticket.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PublicRecordAccessService {
    private static final String KEY_REQUIRED_MESSAGE = "Open the private link you received for this record to continue";
    private static final String KEY_OR_EMAIL_REQUIRED_MESSAGE =
        "Open the private link you received for this record, or verify your email, to continue";

    private final TicketAccessKeys ticketAccessKeys;
    private final TicketEmailVerificationService verificationService;
    private final EmailCapability emailCapability;

    public enum Access {
        GRANTED,
        TOKEN_REQUIRED,
        NOT_FOUND
    }

    public record AccessResult(Access access, @Nullable String emailHint, boolean emailVerified) {
        static AccessResult grantedByAccessKey() {
            return new AccessResult(Access.GRANTED, null, false);
        }

        static AccessResult grantedByEmailVerification() {
            return new AccessResult(Access.GRANTED, null, true);
        }

        static AccessResult notFound() {
            return new AccessResult(Access.NOT_FOUND, null, false);
        }

        static AccessResult tokenRequired(@Nullable String emailHint) {
            return new AccessResult(Access.TOKEN_REQUIRED, emailHint, false);
        }

        public String accessRequiredMessage() {
            return emailHint == null ? KEY_REQUIRED_MESSAGE : KEY_OR_EMAIL_REQUIRED_MESSAGE;
        }
    }

    public record AccessGrant(Ticket record, boolean emailVerified) {
    }

    public AccessGrant requireAccess(Server server, @Nullable Ticket record, @Nullable String presentedToken) {
        if (record == null) {
            throw new ResourceNotFoundException("Not found");
        }
        AccessResult result = authorize(server, record, presentedToken);
        return switch (result.access()) {
            case NOT_FOUND -> throw new ResourceNotFoundException("Not found");
            case TOKEN_REQUIRED -> throw new RecordAccessRequiredException(
                record.getId(), result.emailHint(), result.accessRequiredMessage());
            case GRANTED -> new AccessGrant(record, result.emailVerified());
        };
    }

    public AccessResult authorize(Server server, @Nullable Ticket record, @Nullable String presentedToken) {
        if (record == null || record.isHidden()) {
            return AccessResult.notFound();
        }
        if (!requiresEmailVerification(record) && ticketAccessKeys.matches(record, presentedToken)) {
            return AccessResult.grantedByAccessKey();
        }
        if (isEmailVerificationToken(server, record, presentedToken)) {
            return AccessResult.grantedByEmailVerification();
        }
        return AccessResult.tokenRequired(emailHint(record));
    }

    private boolean requiresEmailVerification(Ticket record) {
        return record.isEmailAuthEnabled()
            && emailCapability.isDeliveryEnabled()
            && TicketEmailVerificationService.resolveDeliverableEmail(record) != null;
    }

    private boolean isEmailVerificationToken(Server server, Ticket record, @Nullable String presentedToken) {
        return presentedToken != null && !presentedToken.isBlank()
            && verificationService.validateToken(server, record.getId(), presentedToken);
    }

    @Nullable
    private String emailHint(Ticket record) {
        if (!emailCapability.isDeliveryEnabled()) {
            return null;
        }
        String contactEmail = TicketEmailVerificationService.resolveContactEmail(record);
        return contactEmail == null ? null : EmailAddressUtil.mask(contactEmail);
    }
}
