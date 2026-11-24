package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class EndImportJobRequest {
    String reason;
    @Nullable String detail;
}
