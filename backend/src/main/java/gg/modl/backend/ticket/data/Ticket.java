package gg.modl.backend.ticket.data;

import gg.modl.backend.ai.data.AIAnalysisResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ticket {
    private String id;

    private TicketCategory type;

    @Nullable
    private String subject;

    private TicketStatus status;

    @Nullable
    private String creatorUuid;
    @Nullable
    private String creatorName;
    @Nullable
    private String creatorAvatar;

    @Nullable
    private String reportedPlayer;
    @Nullable
    private String reportedPlayerUuid;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Builder.Default
    private List<TicketReply> replies = new ArrayList<>();

    @Builder.Default
    private List<TicketNote> notes = new ArrayList<>();

    @Nullable
    private List<ChatMessage> chatMessages;

    @Nullable
    private Map<String, Object> formData;

    @Nullable
    private Map<String, Object> data;

    private boolean locked;

    @Builder.Default
    private TicketPriority priority = TicketPriority.NORMAL;

    @Builder.Default
    private List<String> assignedTo = new ArrayList<>();

    private Instant created;
    private Instant updatedAt;

    @Nullable
    private AppealWorkflowStatus appealWorkflowStatus;

    @Nullable
    private AIAnalysisResult aiAnalysis;

    @Nullable
    private String replayUrl;

    @Nullable
    private String replayId;

    private boolean emailAuthEnabled;
    private boolean hidden;

    @ToString.Exclude
    private String publicAccessKey;

    private int version;

    public Optional<AppealSubject> appealSubject() {
        return AppealSubject.from(data);
    }

    public Optional<String> appealedPunishmentId() {
        return AppealSubject.punishmentIdOf(data);
    }

    public void applyLifecycleStatus(TicketStatus status) {
        this.status = status;
        this.locked = status != null && status.isTerminal();
    }

    public List<TicketReply> ensureReplies() {
        if (this.replies == null) {
            this.replies = new ArrayList<>();
        }
        return this.replies;
    }

    public List<TicketNote> ensureNotes() {
        if (this.notes == null) {
            this.notes = new ArrayList<>();
        }
        return this.notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String content;

        @Nullable
        private Instant timestamp;

        @Nullable
        private String sender;
    }
}
