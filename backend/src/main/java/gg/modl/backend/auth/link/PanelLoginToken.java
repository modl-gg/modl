package gg.modl.backend.auth.link;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PanelLoginToken {

    private String id;

    private String tokenHash;

    private String identityEmail;

    private @Nullable String minecraftUuid;

    private Instant expiresAt;

    private Instant createdAt;
}
