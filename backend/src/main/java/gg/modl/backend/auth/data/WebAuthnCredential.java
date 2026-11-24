package gg.modl.backend.auth.data;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebAuthnCredential {
    private String id;

    private String email;

    private String credentialId;

    private byte[] publicKeyCose;

    private long signatureCount;

    private String userHandle;

    private String name;

    private Instant createdAt;

    private Instant lastUsedAt;
}
