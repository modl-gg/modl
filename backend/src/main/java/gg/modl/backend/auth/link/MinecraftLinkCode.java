package gg.modl.backend.auth.link;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MinecraftLinkCode {

    private String id;

    private String codeHash;

    private String identityEmail;

    private Instant expiresAt;

    private Instant createdAt;
}
