package gg.modl.backend.auth.repository;

import gg.modl.backend.auth.data.WebAuthnChallenge;
import java.time.Instant;
import java.util.Optional;

public interface WebAuthnChallengeRepository {
    void insert(WebAuthnChallenge challenge);

    Optional<WebAuthnChallenge> consumeActive(String challengeId, Instant now);

    int deleteByEmail(String email);
}
