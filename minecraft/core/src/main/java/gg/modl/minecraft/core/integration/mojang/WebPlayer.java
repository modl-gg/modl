package gg.modl.minecraft.core.integration.mojang;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value
public class WebPlayer {
    private static final WebPlayer INVALID = new WebPlayer(null, null, null, null, false);

    @Nullable String name;
    @Nullable UUID uuid;
    @Nullable String skin;
    @Nullable String textureValue;
    boolean valid;

    public static WebPlayer invalid() {
        return INVALID;
    }
}
