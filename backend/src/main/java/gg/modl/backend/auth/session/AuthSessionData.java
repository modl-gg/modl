package gg.modl.backend.auth.session;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthSessionData {
    @ToString.Exclude
    private String id;

    private String email;

    private Instant createdAt;

    private Instant expiresAt;

    private @Nullable String ipAddress;

    private @Nullable String userAgent;

    @ToString.Exclude
    private String sessionKeyHash;

    private AuthMethod authMethod;

    private boolean secondFactorVerified;

    public String publicId() {
        return SessionPublicId.of(id);
    }
}
