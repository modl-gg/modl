package gg.modl.backend.audit.dto.response;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record PunishmentAuditPage(List<AuditPunishmentResponse> punishments, @Nullable String nextCursor, boolean hasMore) {
}
