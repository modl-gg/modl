package gg.modl.backend.auth.data;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthCode {
    private String email;

    private String codeHash;

    private int failedAttempts;

    private Instant issuedAt;

    private Instant codeExpiresAt;

    private int recentFailures;

    private Instant failureWindowStartedAt;

    private Instant expiresAt;
}
