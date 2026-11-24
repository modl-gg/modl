package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.util.StringUtil;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class RemoteSettings {
    public static final String DEFAULT_URL = "http://localhost:8080";

    @Builder.Default String url = DEFAULT_URL;
    @ToString.Exclude @Builder.Default String apiKey = "";

    public boolean hasApiKey() {
        return !StringUtil.isBlank(apiKey);
    }
}
