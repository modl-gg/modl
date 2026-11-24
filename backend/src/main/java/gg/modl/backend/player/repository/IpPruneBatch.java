package gg.modl.backend.player.repository;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public record IpPruneBatch(long deleted, @Nullable IpHistoryKey resumeAfter) {
    public Optional<IpHistoryKey> next() {
        return Optional.ofNullable(resumeAfter);
    }
}
