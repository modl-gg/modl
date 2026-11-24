package gg.modl.backend.ticket.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.request.AddNoteRequest;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketMessages {
    private static final String STAFF_TYPE = "staff";
    private static final String PLAYER_TYPE = "user";
    private static final String SYSTEM_TYPE = "system";
    private static final String SYSTEM_AUTHOR = "System";

    private final TicketContentService contentService;

    public TicketReply staffReply(AddReplyRequest request, Instant now) {
        return staffReply(request.name(), request.content(), request.action(), request.attachments(), now);
    }

    public TicketReply staffReply(String name, String content, @Nullable String action,
                                  @Nullable List<Object> attachments, Instant now) {
        return TicketReply.builder()
            .id(EntityIds.next())
            .name(name)
            .content(content)
            .type(STAFF_TYPE)
            .created(now)
            .staff(true)
            .action(action)
            .attachments(attachments != null ? attachments : new ArrayList<>())
            .build();
    }

    public TicketReply playerReply(@Nullable String name, String content, @Nullable List<Object> attachments, Instant now) {
        TicketInputLimits.requirePlayerReply(content);
        TicketInputLimits.requireAttachments(attachments);
        return TicketReply.builder()
            .id(EntityIds.next())
            .name(name)
            .content(content)
            .type(PLAYER_TYPE)
            .created(now)
            .staff(false)
            .attachments(contentService.normalizeAttachments(attachments))
            .build();
    }

    public TicketNote requestedNote(AddNoteRequest request, String issuerName, Instant now) {
        return TicketNote.builder()
            .text(request.text())
            .issuerName(issuerName)
            .attachments(contentService.normalizeAttachments(request.attachments()))
            .date(now)
            .build();
    }

    public static TicketReply systemReply(@Nullable String author, String content, String action, Instant now) {
        return TicketReply.builder()
            .id(EntityIds.next())
            .name(author != null ? author : SYSTEM_AUTHOR)
            .content(content)
            .type(SYSTEM_TYPE)
            .created(now)
            .staff(true)
            .action(action)
            .build();
    }
}
