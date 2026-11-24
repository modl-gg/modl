package gg.modl.backend.ticket.repository;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.TicketStatus;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
public record TicketHeaderPatch(
    Instant updatedAt,
    @Nullable TicketStatus status,
    @Nullable Boolean locked,
    @Nullable Boolean hidden,
    @Nullable Boolean emailAuthEnabled,
    @Nullable String subject,
    @Nullable String creatorName,
    @Nullable String creatorUuid,
    @Nullable AppealWorkflowStatus appealWorkflowStatus,
    @Nullable Map<String, Object> formData,
    @Nullable Map<String, Object> data,
    @Nullable AIAnalysisResult aiAnalysis
) {
}
