package gg.modl.backend.infrastructure.validation;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;

public final class RequiredText {
    private RequiredText() {
    }

    @Nullable
    public static String notBlankIfPresent(@Nullable String value, String label) {
        if (value != null && value.isBlank()) {
            throw new ValidationException(label + " cannot be blank");
        }
        return value;
    }
}
