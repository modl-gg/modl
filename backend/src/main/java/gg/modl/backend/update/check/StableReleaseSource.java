package gg.modl.backend.update.check;

import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.state.ReleaseChannelState;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.SignedRelease;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StableReleaseSource {
    private final ReleaseHttpClient http;
    private final ReleaseEnvelopes envelopes;
    private final URI latestEnvelope;

    public StableReleaseSource(ReleaseHttpClient http, ReleaseEnvelopes envelopes, UpdateProperties properties) {
        this.http = http;
        this.envelopes = envelopes;
        this.latestEnvelope = URI.create(properties.baseUrl() + "/latest/download/" + SignedRelease.ENVELOPE_FILE);
    }

    SourceResult fetch(ReleaseChannelState previous) {
        Optional<SignedRelease> kept = envelopes.reverify(previous.envelope());
        String etag = kept.isPresent() ? previous.etag() : null;
        ReleaseResponse response;
        try {
            response = http.get(latestEnvelope, etag, SignedRelease.MAX_ENVELOPE_BYTES, false);
        } catch (IOException e) {
            log.debug("The stable release check failed", e);
            return SourceResult.failed(CheckOutcome.NETWORK_ERROR, previous, kept, null);
        }
        return switch (response.status()) {
            case ReleaseResponse.NOT_MODIFIED -> kept.isPresent()
                ? SourceResult.succeeded(previous, kept)
                : SourceResult.failed(CheckOutcome.NETWORK_ERROR, ReleaseChannelState.EMPTY, Optional.empty(), null);
            case ReleaseResponse.NOT_FOUND -> new SourceResult(CheckOutcome.NO_RELEASE, ReleaseChannelState.EMPTY, Optional.empty(), null);
            case ReleaseResponse.OK -> verified(response, previous, kept);
            default -> SourceResult.failed(response.isRateLimited() ? CheckOutcome.RATE_LIMITED : CheckOutcome.NETWORK_ERROR, previous,
                kept, response.retryAfter());
        };
    }

    private SourceResult verified(ReleaseResponse response, ReleaseChannelState previous, Optional<SignedRelease> kept) {
        try {
            SignedRelease release = envelopes.verify(response.body());
            return SourceResult.succeeded(new ReleaseChannelState(ReleaseEnvelopes.text(response.body()), response.etag()),
                Optional.of(release));
        } catch (ReleaseVerificationException e) {
            log.warn("Ignored the latest modl release: {}", e.getMessage());
            return SourceResult.failed(CheckOutcome.of(e.reason()), previous, kept, null);
        }
    }
}
