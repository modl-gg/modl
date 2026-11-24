package gg.modl.backend.update.install;

import gg.modl.update.SignedRelease;

public interface UpdateInstaller {
    InstallState state();

    Installability availability(SignedRelease release);

    void install(InstallCommand command);
}
