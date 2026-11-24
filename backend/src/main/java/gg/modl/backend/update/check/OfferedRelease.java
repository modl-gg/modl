package gg.modl.backend.update.check;

import gg.modl.backend.update.state.InstanceState;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OfferedRelease {
    private final ReleaseEnvelopes envelopes;

    public Optional<SignedRelease> newerThan(ReleaseVersion current, InstanceState state) {
        Optional<SignedRelease> stable = envelopes.reverify(state.stable().envelope());
        Optional<SignedRelease> prerelease = state.includePrereleases()
            ? envelopes.reverify(state.prerelease().envelope())
            : Optional.empty();
        return highestNewer(current, stable, prerelease);
    }

    static Optional<SignedRelease> highestNewer(ReleaseVersion current, Optional<SignedRelease> stable,
                                                Optional<SignedRelease> prerelease) {
        return Stream.concat(stable.stream(), prerelease.stream())
            .filter(release -> release.version().isNewerThan(current))
            .max((left, right) -> left.version().compareTo(right.version()));
    }
}
