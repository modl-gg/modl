package gg.modl.backend.server.service;

import org.jspecify.annotations.Nullable;

public class ProvisioningException extends RuntimeException {
    public ProvisioningException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
