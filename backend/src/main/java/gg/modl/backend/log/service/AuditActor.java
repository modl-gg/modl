package gg.modl.backend.log.service;

import gg.modl.backend.email.EmailAddressUtil;
import org.jspecify.annotations.Nullable;

public record AuditActor(String displayName) {
    static final String SYSTEM = "system";
    static final String UNKNOWN_STAFF = "Unknown Staff";

    public AuditActor {
        displayName = displayNameFor(displayName);
    }

    public static AuditActor system() {
        return new AuditActor(SYSTEM);
    }

    public static AuditActor named(@Nullable String name) {
        return new AuditActor(displayNameFor(name));
    }

    public static String displayNameFor(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return SYSTEM;
        }
        return EmailAddressUtil.isValid(name) ? UNKNOWN_STAFF : name.strip();
    }
}
