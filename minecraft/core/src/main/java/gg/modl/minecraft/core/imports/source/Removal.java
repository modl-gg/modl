package gg.modl.minecraft.core.imports.source;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class Removal {
    String by;
    @Nullable Long at;
    @Nullable String reason;
}
