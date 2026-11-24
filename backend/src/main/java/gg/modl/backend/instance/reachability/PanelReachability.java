package gg.modl.backend.instance.reachability;

import gg.modl.backend.instance.setup.PublicUrlVerdict;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Optional;

public record PanelReachability(Outcome outcome, @Nullable String url, @Nullable Instant checkedAt) {
    public static final PanelReachability NOT_CHECKED = new PanelReachability(Outcome.NOT_CHECKED, null, null);

    static PanelReachability of(PublicUrlVerdict verdict, String url, Instant checkedAt) {
        return new PanelReachability(Outcome.of(verdict), url, checkedAt);
    }

    static PanelReachability unknownUrl(Instant checkedAt) {
        return new PanelReachability(Outcome.URL_UNKNOWN, null, checkedAt);
    }

    public Optional<String> checkedUrl() {
        return Optional.ofNullable(url);
    }

    public enum Outcome {
        NOT_CHECKED,
        URL_UNKNOWN,
        REACHABLE,
        UNREACHABLE,
        OTHER_SERVICE;

        static Outcome of(PublicUrlVerdict verdict) {
            return switch (verdict) {
                case REACHABLE -> REACHABLE;
                case OTHER_SERVICE -> OTHER_SERVICE;
                case UNREACHABLE, INVALID -> UNREACHABLE;
            };
        }
    }
}
