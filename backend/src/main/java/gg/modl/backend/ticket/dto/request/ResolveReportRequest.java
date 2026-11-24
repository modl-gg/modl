package gg.modl.backend.ticket.dto.request;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record ResolveReportRequest(
    @Size(max = RequestValidationLimits.REPORT_STAFF_NAME_MAX_LENGTH)
    @NotBlank
    String resolvedBy,

    @Size(max = RequestValidationLimits.REPORT_REASON_MAX_LENGTH)
    String resolution,

    @Size(max = RequestValidationLimits.REPORT_PUNISHMENT_ID_MAX_LENGTH)
    @NotBlank
    String punishmentId,

    @Nullable
    @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH)
    String issuerId,

    @Nullable Boolean console
) {
}