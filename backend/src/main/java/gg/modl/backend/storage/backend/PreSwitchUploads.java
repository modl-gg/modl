package gg.modl.backend.storage.backend;

import java.time.Instant;

@FunctionalInterface
interface PreSwitchUploads {
    boolean anyOpenFrom(Instant grantedBefore);
}
