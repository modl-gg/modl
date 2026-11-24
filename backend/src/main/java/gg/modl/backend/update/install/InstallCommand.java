package gg.modl.backend.update.install;

import gg.modl.update.SignedRelease;

public record InstallCommand(SignedRelease release, String requestedBy) {
}
