package gg.modl.backend.instance;

import gg.modl.backend.serverkey.service.ServerKeyCache;
import gg.modl.backend.serverkey.service.ServerKeyLastSeen;
import gg.modl.backend.serverkey.service.ServerKeySecrets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MinecraftCredentialVerifier {
    private final InstanceContext instanceContext;
    private final InstanceProperties instanceProperties;
    private final ServerKeyCache serverKeys;
    private final ServerKeyLastSeen lastSeen;

    public Optional<MinecraftCredential> verify(@Nullable String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        Optional<MinecraftCredential> builtIn = builtInKey(presentedKey);
        if (builtIn.isPresent()) {
            return builtIn;
        }
        if (!ServerKeySecrets.looksLikeServerKey(presentedKey)) {
            return Optional.empty();
        }
        return serverKeys.findByHash(ServerKeySecrets.hash(presentedKey))
            .map(key -> {
                lastSeen.touch(key.id());
                return MinecraftCredential.of(key);
            });
    }

    public boolean isValid(@Nullable String presentedKey) {
        return verify(presentedKey).isPresent();
    }

    private Optional<MinecraftCredential> builtInKey(String presentedKey) {
        byte[] presented = presentedKey.getBytes(StandardCharsets.UTF_8);
        boolean matchesInstanceKey = matches(presented, Optional.ofNullable(instanceContext.instance().getApiKey()));
        boolean matchesInternalKey = matches(presented, instanceProperties.configuredInternalApiKey());
        if (matchesInternalKey) {
            return Optional.of(MinecraftCredential.internalKey());
        }
        return matchesInstanceKey ? Optional.of(MinecraftCredential.instanceKey()) : Optional.empty();
    }

    private static boolean matches(byte[] presented, Optional<String> expected) {
        return expected
            .filter(value -> !value.isBlank())
            .map(value -> MessageDigest.isEqual(presented, value.getBytes(StandardCharsets.UTF_8)))
            .orElse(false);
    }
}
