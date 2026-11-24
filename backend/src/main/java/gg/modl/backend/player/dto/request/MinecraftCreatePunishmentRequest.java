package gg.modl.backend.player.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import gg.modl.backend.infrastructure.validation.RegExpConstants;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

public record MinecraftCreatePunishmentRequest(
    @NotBlank @Pattern(regexp = RegExpConstants.UUID) String targetUuid,
    @Size(max = RequestValidationLimits.PLAYER_ISSUER_NAME_MAX_LENGTH) @Nullable String issuerName,
    @Size(max = RequestValidationLimits.PLAYER_ISSUER_ID_MAX_LENGTH) @Nullable String issuerId,
    @JsonProperty("type_ordinal") @Min(0) int typeOrdinal,
    @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH) @Nullable String reason,
    @Min(0) @Max(RequestValidationLimits.PUNISHMENT_DURATION_MAX_MS) @Nullable Long duration,
    @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_DATA_MAX_ENTRIES) @Nullable Map<String, Object> data,
    @Nullable @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_NOTES_MAX_ENTRIES) List<@Size(max = RequestValidationLimits.PLAYER_NOTE_TEXT_MAX_LENGTH) String> notes,
    @Nullable @Size(max = RequestValidationLimits.PLAYER_PUNISHMENT_TICKETS_MAX_ENTRIES) List<@Size(max = RequestValidationLimits.ID_MAX_LENGTH) String> attachedTicketIds,
    @Size(max = RequestValidationLimits.PLAYER_SEVERITY_MAX_LENGTH) @Nullable String severity,
    @Size(max = RequestValidationLimits.PLAYER_STATUS_MAX_LENGTH) @Nullable String status,
    @Nullable Boolean console
) {
}
