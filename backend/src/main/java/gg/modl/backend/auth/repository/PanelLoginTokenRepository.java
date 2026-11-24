package gg.modl.backend.auth.repository;

import gg.modl.backend.auth.link.PanelLoginToken;
import java.time.Instant;
import java.util.Optional;

public interface PanelLoginTokenRepository {
    void insert(PanelLoginToken token);

    void deleteForIdentity(String identityEmail);

    Optional<PanelLoginToken> consumeActive(String tokenHash, Instant now);
}
