package gg.modl.backend.ticket.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.infrastructure.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContactEmailPolicy {
    private final EmailCapability emailCapability;

    public enum Requirement {
        REQUIRED,
        OPTIONAL
    }

    public Requirement forTickets() {
        return Requirement.OPTIONAL;
    }

    public Requirement forAppeals() {
        return emailCapability.isDeliveryEnabled() ? Requirement.REQUIRED : Requirement.OPTIONAL;
    }

    @Nullable
    public String accept(@Nullable String email, Requirement requirement) {
        String normalized = EmailAddressUtil.normalize(email);
        if (normalized == null) {
            if (requirement == Requirement.REQUIRED) {
                throw new ValidationException("An email address is required");
            }
            return null;
        }
        if (!EmailAddressUtil.isValid(normalized)) {
            throw new ValidationException("A valid email address is required");
        }
        return normalized;
    }
}
