package gg.modl.backend.auth.repository;

import gg.modl.backend.auth.link.MinecraftLinkCode;
import java.time.Instant;
import java.util.Optional;

public interface MinecraftLinkCodeRepository {
    void replaceForIdentity(MinecraftLinkCode code);

    void deleteForIdentity(String identityEmail);

    Optional<MinecraftLinkCode> findActive(String codeHash, Instant now);

    Optional<MinecraftLinkCode> consumeActive(String codeHash, Instant now);
}
