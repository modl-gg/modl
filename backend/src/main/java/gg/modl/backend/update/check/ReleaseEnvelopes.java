package gg.modl.backend.update.check;

import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.SignedRelease;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReleaseEnvelopes {
    private final ReleaseKeys keys;

    public SignedRelease verify(byte[] envelope) throws ReleaseVerificationException {
        return SignedRelease.verify(envelope, keys, JacksonJsonMaps.INSTANCE);
    }

    public Optional<SignedRelease> reverify(@Nullable String storedEnvelope) {
        if (storedEnvelope == null || keys.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(verify(storedEnvelope.getBytes(StandardCharsets.UTF_8)));
        } catch (ReleaseVerificationException e) {
            log.warn("Ignored a stored release that no longer verifies: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    static String text(byte[] envelope) {
        return new String(envelope, StandardCharsets.UTF_8);
    }
}
