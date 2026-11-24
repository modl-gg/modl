package gg.modl.backend.iplookup.settings;

import org.jspecify.annotations.Nullable;
import java.time.Instant;

public record AddressForwardingNotice(
    @Nullable Reason reason,
    @Nullable String serverName,
    @Nullable Long detectedAt,
    @Nullable Long dismissedAt
) {
    public static final AddressForwardingNotice NONE = new AddressForwardingNotice(null, null, null, null);

    public boolean detected() {
        return reason != null && detectedAt != null;
    }

    public boolean showing() {
        return detected() && dismissedAt == null;
    }

    public AddressForwardingNotice dismissedAt(Instant at) {
        return new AddressForwardingNotice(reason, serverName, detectedAt, at.toEpochMilli());
    }

    public static AddressForwardingNotice detected(Reason reason, @Nullable String serverName, Instant at) {
        return new AddressForwardingNotice(reason, serverName, at.toEpochMilli(), null);
    }

    public enum Reason {
        NON_PUBLIC_ADDRESS,
        SHARED_ADDRESS
    }
}
