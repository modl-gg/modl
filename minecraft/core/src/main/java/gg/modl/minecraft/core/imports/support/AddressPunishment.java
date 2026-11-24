package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.SourcePunishment;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class AddressPunishment {
    @Nullable String address;
    SourcePunishment punishment;
}
