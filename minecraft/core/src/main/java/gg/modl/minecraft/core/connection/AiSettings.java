package gg.modl.minecraft.core.connection;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class AiSettings {
    @Builder.Default String provider = "";
    @ToString.Exclude @Builder.Default String apiKey = "";
    @Builder.Default String model = "";
    @Builder.Default String baseUrl = "";
}
