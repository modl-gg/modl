package gg.modl.backend.auth.data;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebAuthnChallenge {
    private String id;

    private String challengeJson;

    private @Nullable String email;

    private Instant expiresAt;
}
