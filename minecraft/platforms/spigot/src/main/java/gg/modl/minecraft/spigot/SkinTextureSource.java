package gg.modl.minecraft.spigot;

import org.jspecify.annotations.Nullable;

interface SkinTextureSource {
    SkinTextureSource NONE = player -> null;

    @Nullable String texturesOf(Object player) throws ReflectiveOperationException;
}
