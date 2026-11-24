package gg.modl.backend.auth.signin;

import io.github.bucket4j.Bucket;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AttemptReservation {
    private final List<Bucket> consumed;
    @Nullable
    private final Bucket clearedOnSuccess;
    private final AtomicBoolean settled = new AtomicBoolean();

    AttemptReservation(List<Bucket> consumed, @Nullable Bucket clearedOnSuccess) {
        this.consumed = List.copyOf(consumed);
        this.clearedOnSuccess = clearedOnSuccess;
    }

    public void refund() {
        if (settled.compareAndSet(false, true)) {
            consumed.forEach(bucket -> bucket.addTokens(1));
        }
    }

    public void succeeded() {
        refund();
        if (clearedOnSuccess != null) {
            clearedOnSuccess.reset();
        }
    }
}
