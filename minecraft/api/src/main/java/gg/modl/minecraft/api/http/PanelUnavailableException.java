package gg.modl.minecraft.api.http;

import lombok.Getter;
import org.jspecify.annotations.Nullable;

@Getter
public class PanelUnavailableException extends RuntimeException {
    private final String endpoint;
    private final int statusCode;
    private final @Nullable String code;

    public PanelUnavailableException(String endpoint, int statusCode, String message) {
        this(endpoint, statusCode, message, null);
    }

    public PanelUnavailableException(String endpoint, int statusCode, String message, @Nullable String code) {
        super(message);
        this.endpoint = endpoint;
        this.statusCode = statusCode;
        this.code = code != null && !code.isEmpty() ? code : null;
    }
}
