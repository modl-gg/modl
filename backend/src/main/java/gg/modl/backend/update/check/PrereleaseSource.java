package gg.modl.backend.update.check;

import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.state.ReleaseChannelState;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
public class PrereleaseSource {
    static final int MAX_LIST_BYTES = 1024 * 1024;
    private static final String TAG_PREFIX = "v";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ReleaseHttpClient http;
    private final ReleaseEnvelopes envelopes;
    private final UpdateProperties properties;
    private final URI releaseList;

    public PrereleaseSource(ReleaseHttpClient http, ReleaseEnvelopes envelopes, UpdateProperties properties) {
        this.http = http;
        this.envelopes = envelopes;
        this.properties = properties;
        this.releaseList = URI.create(properties.apiUrl() + "?per_page=20");
    }

    SourceResult fetch(ReleaseChannelState previous) {
        Optional<SignedRelease> kept = envelopes.reverify(previous.envelope());
        ReleaseResponse list;
        try {
            String etag = previous.envelope() == null || kept.isPresent() ? previous.etag() : null;
            list = http.get(releaseList, etag, MAX_LIST_BYTES, true);
        } catch (IOException e) {
            log.debug("The pre-release check failed", e);
            return SourceResult.failed(CheckOutcome.NETWORK_ERROR, previous, kept, null);
        }
        if (list.status() == ReleaseResponse.NOT_MODIFIED) {
            return SourceResult.succeeded(previous, kept);
        }
        if (list.status() != ReleaseResponse.OK) {
            return SourceResult.failed(list.isRateLimited() ? CheckOutcome.RATE_LIMITED : CheckOutcome.NETWORK_ERROR, previous, kept,
                list.retryAfter());
        }
        Optional<ReleaseVersion> highest;
        try {
            highest = highestPublished(list.body());
        } catch (JacksonException e) {
            return SourceResult.failed(CheckOutcome.INVALID_MANIFEST, previous, kept, null);
        }
        if (highest.isEmpty()) {
            return SourceResult.succeeded(new ReleaseChannelState(null, list.etag()), Optional.empty());
        }
        if (kept.isPresent() && kept.get().version().equals(highest.get())) {
            return SourceResult.succeeded(new ReleaseChannelState(previous.envelope(), list.etag()), kept);
        }
        return download(highest.get(), list.etag(), previous, kept);
    }

    private SourceResult download(ReleaseVersion version, @Nullable String listEtag, ReleaseChannelState previous, Optional<SignedRelease> kept) {
        URI envelope = URI.create(properties.baseUrl() + "/download/" + TAG_PREFIX + version + "/" + SignedRelease.ENVELOPE_FILE);
        try {
            ReleaseResponse response = http.get(envelope, null, SignedRelease.MAX_ENVELOPE_BYTES, false);
            if (response.status() != ReleaseResponse.OK) {
                return SourceResult.failed(response.isRateLimited() ? CheckOutcome.RATE_LIMITED : CheckOutcome.NETWORK_ERROR, previous,
                    kept, response.retryAfter());
            }
            SignedRelease release = envelopes.verify(response.body());
            if (!release.version().equals(version)) {
                return SourceResult.failed(CheckOutcome.INVALID_MANIFEST, previous, kept, null);
            }
            return SourceResult.succeeded(new ReleaseChannelState(ReleaseEnvelopes.text(response.body()), listEtag), Optional.of(release));
        } catch (IOException e) {
            log.debug("The pre-release envelope download failed", e);
            return SourceResult.failed(CheckOutcome.NETWORK_ERROR, previous, kept, null);
        } catch (ReleaseVerificationException e) {
            log.warn("Ignored modl pre-release {}: {}", version, e.getMessage());
            return SourceResult.failed(CheckOutcome.of(e.reason()), previous, kept, null);
        }
    }

    static Optional<ReleaseVersion> highestPublished(byte[] list) {
        JsonNode releases = MAPPER.readTree(list);
        ReleaseVersion highest = null;
        for (JsonNode release : releases) {
            if (release.path("draft").asBoolean(true)) {
                continue;
            }
            String tag = release.path("tag_name").asString("");
            ReleaseVersion version = tag.startsWith(TAG_PREFIX) ? ReleaseVersion.tryParse(tag.substring(TAG_PREFIX.length())) : null;
            if (version != null && (highest == null || version.isNewerThan(highest))) {
                highest = version;
            }
        }
        return Optional.ofNullable(highest);
    }
}
