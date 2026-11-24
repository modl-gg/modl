package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.settings.data.PunishmentType;
import org.jspecify.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class PanelPunishmentRequests {
    private static final Set<String> BACKEND_DECIDED_KEYS = Set.of(
        PunishmentData.PENDING_ACKNOWLEDGEMENT,
        "aiGenerated");

    private PanelPunishmentRequests() {
    }

    static CreatePunishmentRequest issuedBy(PunishmentIssuer issuer, @Nullable PunishmentType type,
                                            CreatePunishmentRequest request) {
        return new CreatePunishmentRequest(
            issuer.name(),
            issuer.id(),
            issuer.kind(),
            request.typeOrdinal(),
            request.notes(),
            request.evidence(),
            request.attachedTicketIds(),
            severity(type, request),
            request.status(),
            staffSettableData(request.data()),
            request.reason(),
            request.duration());
    }

    @Nullable
    private static String severity(@Nullable PunishmentType type, CreatePunishmentRequest request) {
        boolean durationRequested = request.duration() != null
            || (request.data() != null && PunishmentDataView.ofMap(request.data()).duration() != null);
        if (request.severity() != null || durationRequested || type == null) {
            return request.severity();
        }
        return type.isSingleSeverityPunishment() && OffenseLevels.escalates(type) ? PunishmentSeverities.SINGLE : null;
    }

    @Nullable
    private static Map<String, Object> staffSettableData(@Nullable Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Map<String, Object> settable = new HashMap<>(data);
        BACKEND_DECIDED_KEYS.forEach(settable::remove);
        return settable;
    }
}
