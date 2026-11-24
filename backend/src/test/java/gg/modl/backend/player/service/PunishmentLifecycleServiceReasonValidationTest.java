package gg.modl.backend.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentLifecycleServiceReasonValidationTest {

    private static final int BAN_ORDINAL = 2;

    private final LifecycleHarness harness = new LifecycleHarness();
    private final Server server = TestServers.server("server", "admin@example.com");
    private final PunishmentLifecycleService lifecycleService = harness.lifecycle;

    @Test
    void rejectsReasonLongerThanLimit() {
        CreatePunishmentRequest request = requestWithReason(overlongReason(), null);

        ValidationException exception = assertThrows(ValidationException.class,
            () -> lifecycleService.createPunishment(server, UUID.randomUUID(), request));

        assertEquals("reason must be at most "
            + RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH + " characters", exception.getMessage());
        verifyNoInteractions(harness.players);
    }

    @Test
    void rejectsDataReasonLongerThanLimit() {
        CreatePunishmentRequest request = requestWithReason(null, Map.of("reason", overlongReason()));

        assertThrows(ValidationException.class,
            () -> lifecycleService.createPunishment(server, UUID.randomUUID(), request));

        verifyNoInteractions(harness.players);
    }

    @Test
    void acceptsReasonAtLimit() {
        String reasonAtLimit = "r".repeat(RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH);
        CreatePunishmentRequest request = requestWithReason(reasonAtLimit, null);

        assertThrows(ResourceNotFoundException.class,
            () -> lifecycleService.createPunishment(server, UUID.randomUUID(), request));
    }

    private String overlongReason() {
        return "r".repeat(RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH + 1);
    }

    private CreatePunishmentRequest requestWithReason(String reason, Map<String, Object> data) {
        return new CreatePunishmentRequest(
            "Staff", null, PunishmentIssuerKind.STAFF, BAN_ORDINAL, null, null, null, null, null, data, reason, null);
    }
}
