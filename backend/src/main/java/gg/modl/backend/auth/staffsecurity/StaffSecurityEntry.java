package gg.modl.backend.auth.staffsecurity;

import org.jspecify.annotations.Nullable;

public record StaffSecurityEntry(@Nullable String staffId, String email, boolean hasPassword, boolean totpEnabled, boolean hasPasskey,
                                 boolean owner) {
    public boolean hasSecondFactor() {
        return totpEnabled || hasPasskey;
    }
}
