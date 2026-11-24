package gg.modl.backend.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.auth.controller.MinecraftPanelLinkController.LinkAccountRequest;
import gg.modl.backend.auth.controller.MinecraftPanelLinkController.LoginLinkRequest;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.replay.dto.InitReplayUploadRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RequestDtoValidationTest {
    private static final String VALID_UUID = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private void assertHasViolation(Set<? extends ConstraintViolation<?>> violations, String propertyPath) {
        assertFalse(violations.isEmpty(), "Expected validation to fail");
        assertTrue(
            violations.stream().anyMatch(violation -> propertyPath.equals(violation.getPropertyPath().toString())),
            () -> "Expected violation for property '" + propertyPath + "' but got " +
                  violations.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList()
        );
    }

    @Test
    void legacyReplayUploadRejectsInvalidTargetUuid() {
        InitReplayUploadRequest request = new InitReplayUploadRequest(
            "1.21.4",
            1024,
            "not-a-uuid",
            "byteful"
        );

        assertHasViolation(validator.validate(request), "targetUuid");
    }

    @Test
    void legacyReplayUploadRejectsLongTargetName() {
        InitReplayUploadRequest request = new InitReplayUploadRequest(
            "1.21.4",
            1024,
            VALID_UUID,
            "x".repeat(RequestValidationLimits.LOG_USERNAME_MAX_LENGTH + 1)
        );

        assertHasViolation(validator.validate(request), "targetName");
    }

    @Test
    void linkAccountRequestRejectsInvalidUuidUsernameAndMissingCode() {
        Set<ConstraintViolation<LinkAccountRequest>> violations =
            validator.validate(new LinkAccountRequest("not-a-uuid", "bad name!", ""));

        assertHasViolation(violations, "minecraftUuid");
        assertHasViolation(violations, "minecraftUsername");
        assertHasViolation(violations, "code");
    }

    @Test
    void linkAccountRequestAcceptsValidInput() {
        assertTrue(validator.validate(new LinkAccountRequest(VALID_UUID, "byteful", "ABCD2345")).isEmpty());
    }

    @Test
    void loginLinkRequestRejectsInvalidUuid() {
        assertHasViolation(validator.validate(new LoginLinkRequest("not-a-uuid")), "minecraftUuid");
    }
}
