package gg.modl.minecraft.core.replay;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value
public class ReplayUploadRequest {
    String mcVersion;
    long fileSize;
    @Nullable UUID targetUuid;
    @Nullable String targetName;
}
