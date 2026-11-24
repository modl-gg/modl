package gg.modl.backend.storage;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public record PublicUpload(String clientNetwork, @Nullable String recordPrefix) {
    public static PublicUpload unattached(String clientNetwork) {
        return new PublicUpload(clientNetwork, null);
    }

    public static PublicUpload forRecord(String clientNetwork, String recordPrefix) {
        return new PublicUpload(clientNetwork, recordPrefix);
    }

    Optional<String> record() {
        return Optional.ofNullable(recordPrefix);
    }
}
