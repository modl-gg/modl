package gg.modl.backend.update.check;

import gg.modl.update.SignedRelease;

public final class DockerCommands {
    public static final String PULL_AND_RESTART = "docker compose pull && docker compose up -d";

    private DockerCommands() {
    }

    public static String imageReference(SignedRelease release) {
        return release.version() + "@" + release.manifest().imageDigest();
    }

    public static String howToUpdate(SignedRelease release) {
        return "Set MODL_IMAGE_TAG=" + imageReference(release) + " in .env, then run: " + PULL_AND_RESTART;
    }
}
