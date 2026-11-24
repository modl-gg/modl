package gg.modl.backend.ticket.dto.response;

import gg.modl.backend.ai.data.AIAnalysisResult;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TicketResponse(
    String id,
    String type,
    String category,
    String subject,
    String status,
    @Nullable String appealWorkflowStatus,
    String creatorName,
    @Nullable String creatorUuid,
    String reportedBy,
    @Nullable String reportedPlayer,
    @Nullable String reportedPlayerUuid,
    Instant date,
    boolean locked,
    List<TicketReply> messages,
    List<TicketNote> notes,
    List<String> tags,
    @Nullable Map<String, Object> formData,
    @Nullable Map<String, Object> data,
    @Nullable List<Ticket.ChatMessage> chatMessages,
    @Nullable AIAnalysisResult aiAnalysis,
    boolean emailAuthEnabled,
    boolean hidden,
    @Nullable String replayUrl,
    List<String> assignedTo,
    @Nullable String publicUrl
) {
    public TicketResponse withoutNotes() {
        return new TicketResponse(id, type, category, subject, status, appealWorkflowStatus, creatorName, creatorUuid,
            reportedBy, reportedPlayer, reportedPlayerUuid, date, locked, messages, List.of(), tags, formData, data,
            chatMessages, aiAnalysis, emailAuthEnabled, hidden, replayUrl, assignedTo, publicUrl);
    }

    public boolean creatorLinked() {
        return creatorUuid != null && !creatorUuid.isBlank();
    }
}
