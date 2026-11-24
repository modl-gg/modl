package gg.modl.backend.ticket.service;

import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PublicRecordVerificationService {
    private final TicketEmailVerificationService verificationService;
    private final EmailCapability emailCapability;

    public Ticket requireVerifiableRecord(@Nullable Ticket record) {
        if (record == null || record.isHidden()) {
            throw new ResourceNotFoundException("Not found");
        }
        return record;
    }

    public String sendVerificationCode(Server server, Ticket record) {
        if (!emailCapability.isDeliveryEnabled()) {
            throw new ValidationException("Email verification is not available on this server; open the link you received when you created this record");
        }
        if (TicketEmailVerificationService.resolveContactEmail(record) == null) {
            throw new ValidationException("No email associated with this record");
        }
        return verificationService.sendVerificationCode(server, record);
    }

    public String verifyCode(Server server, String recordId, String code, String clientNetwork) {
        String token = verificationService.verifyCode(server, recordId, code, clientNetwork);
        if (token == null) {
            throw new ForbiddenException("Invalid or expired code");
        }
        return token;
    }
}
