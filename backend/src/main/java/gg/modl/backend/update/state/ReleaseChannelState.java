package gg.modl.backend.update.state;

import org.jspecify.annotations.Nullable;

public record ReleaseChannelState(@Nullable String envelope, @Nullable String etag) {
    public static final ReleaseChannelState EMPTY = new ReleaseChannelState(null, null);
}
