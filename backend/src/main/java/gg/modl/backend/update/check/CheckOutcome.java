package gg.modl.backend.update.check;

import gg.modl.update.ReleaseVerificationException;

public enum CheckOutcome {
    SUCCEEDED,
    NO_RELEASE,
    NETWORK_ERROR,
    RATE_LIMITED,
    INVALID_SIGNATURE,
    INVALID_MANIFEST,
    UNSUPPORTED_FORMAT;

    static CheckOutcome of(ReleaseVerificationException.Reason reason) {
        return switch (reason) {
            case INVALID_SIGNATURE -> INVALID_SIGNATURE;
            case INVALID_MANIFEST -> INVALID_MANIFEST;
            case UNSUPPORTED_FORMAT -> UNSUPPORTED_FORMAT;
        };
    }

    public boolean isFailure() {
        return this != SUCCEEDED && this != NO_RELEASE;
    }

    public static CheckOutcome fromStored(String stored) {
        for (CheckOutcome outcome : values()) {
            if (outcome.name().equals(stored)) {
                return outcome;
            }
        }
        return NETWORK_ERROR;
    }
}
