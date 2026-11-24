package gg.modl.backend.update.check;

import gg.modl.backend.update.state.ReleaseChannelState;
import gg.modl.update.SignedRelease;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.util.Optional;

record SourceResult(CheckOutcome outcome, ReleaseChannelState state, Optional<SignedRelease> release, @Nullable Duration retryAfter) {
    static SourceResult succeeded(ReleaseChannelState state, Optional<SignedRelease> release) {
        return new SourceResult(CheckOutcome.SUCCEEDED, state, release, null);
    }

    static SourceResult failed(CheckOutcome outcome, ReleaseChannelState kept, Optional<SignedRelease> keptRelease,
                               @Nullable Duration retryAfter) {
        return new SourceResult(outcome, kept, keptRelease, retryAfter);
    }
}
