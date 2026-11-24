package gg.modl.minecraft.core.boot;

import java.io.IOException;

final class CredentialsRejectedException extends IOException {
    CredentialsRejectedException(HttpStatusException cause) {
        super("the API key was rejected (HTTP " + cause.statusCode() + ")", cause);
    }
}
