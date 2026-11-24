package gg.modl.minecraft.core.replay;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Value
public class ReplayUploadGrant {
    private static final String DEFAULT_METHOD = "PUT";

    String replayId;
    String uploadUrl;
    String method;
    Map<String, String> requiredHeaders;

    public ReplayUploadGrant(String replayId, String uploadUrl, @Nullable String method,
                             @Nullable Map<String, String> requiredHeaders) {
        this.replayId = replayId;
        this.uploadUrl = uploadUrl;
        this.method = method == null || method.trim().isEmpty() ? DEFAULT_METHOD : method.trim().toUpperCase(Locale.ROOT);
        this.requiredHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(
                requiredHeaders == null ? Collections.<String, String>emptyMap() : requiredHeaders));
    }
}
