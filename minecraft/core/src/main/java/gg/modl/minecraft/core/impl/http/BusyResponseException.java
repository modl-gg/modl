package gg.modl.minecraft.core.impl.http;

import lombok.Getter;

import java.time.Duration;

@Getter
final class BusyResponseException extends RuntimeException {
    private final Duration retryDelay;

    BusyResponseException(Duration retryDelay) {
        super(null, null, false, false);
        this.retryDelay = retryDelay;
    }
}
