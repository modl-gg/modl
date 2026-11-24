package gg.modl.backend.update.check;

import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.state.ReleaseChannelState;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.ReleaseVerificationException;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;

public final class ReleaseLookup {
    private final ReleaseHttpClient http;
    private final ReleaseEnvelopes envelopes;
    private final StableReleaseSource stable;
    private final PrereleaseSource prerelease;
    private final UpdateProperties properties;

    public ReleaseLookup(UpdateProperties properties, ReleaseBuild build, ReleaseKeys keys, Clock clock) {
        this.properties = properties;
        this.http = new ReleaseHttpClient(properties, build, clock);
        this.envelopes = new ReleaseEnvelopes(keys);
        this.stable = new StableReleaseSource(http, envelopes, properties);
        this.prerelease = new PrereleaseSource(http, envelopes, properties);
    }

    public Optional<SignedRelease> newest(ReleaseVersion current, boolean includePrereleases) throws IOException {
        SourceResult stableResult = stable.fetch(ReleaseChannelState.EMPTY);
        requireAnswered(stableResult);
        Optional<SignedRelease> prereleaseRelease = Optional.empty();
        if (includePrereleases) {
            SourceResult prereleaseResult = prerelease.fetch(ReleaseChannelState.EMPTY);
            requireAnswered(prereleaseResult);
            prereleaseRelease = prereleaseResult.release();
        }
        return OfferedRelease.highestNewer(current, stableResult.release(), prereleaseRelease);
    }

    public SignedRelease exact(ReleaseVersion version) throws IOException {
        URI envelope = URI.create(properties.baseUrl() + "/download/v" + version + "/" + SignedRelease.ENVELOPE_FILE);
        ReleaseResponse response = http.get(envelope, null, SignedRelease.MAX_ENVELOPE_BYTES, false);
        if (response.status() != ReleaseResponse.OK) {
            throw new IOException("modl " + version + " was not found (HTTP " + response.status() + ")");
        }
        try {
            SignedRelease release = envelopes.verify(response.body());
            if (!release.version().equals(version)) {
                throw new IOException("The release file of modl " + version + " describes modl " + release.version());
            }
            return release;
        } catch (ReleaseVerificationException e) {
            throw new IOException("The release file of modl " + version + " does not verify: " + e.getMessage(), e);
        }
    }

    public ReleaseHttpClient http() {
        return http;
    }

    private static void requireAnswered(SourceResult result) throws IOException {
        if (result.outcome().isFailure()) {
            throw new IOException("The release check failed (" + result.outcome() + ")");
        }
    }
}
