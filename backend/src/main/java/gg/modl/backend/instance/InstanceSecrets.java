package gg.modl.backend.instance;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString(onlyExplicitlyIncluded = true)
public class InstanceSecrets {
    public static final String SINGLETON_ID = "instance";

    @ToString.Include
    private String id;

    private String authCodeHashSecret;

    private String storageSigningKey;

    private String setupCodeHash;

    private Instant setupCodeExpiresAt;

    private Integer setupCodeFailedAttempts;

    private Instant setupCodeIssuedAt;

    private Instant createdAt;
}
