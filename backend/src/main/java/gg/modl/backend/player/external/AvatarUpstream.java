package gg.modl.backend.player.external;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
interface AvatarUpstream {
    byte @Nullable [] fetch(String uuid, int size, boolean overlay);
}
