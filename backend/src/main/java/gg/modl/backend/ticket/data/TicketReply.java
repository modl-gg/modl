package gg.modl.backend.ticket.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TicketReply {
    private String id;
    @Nullable
    private String name;
    @Nullable
    private String avatar;
    @Nullable
    private String content;
    @Nullable
    private String type;
    private Instant created;
    private boolean staff;
    @Nullable
    private String action;
    @Builder.Default
    private List<Object> attachments = new ArrayList<>();
    @Nullable
    private String creatorIdentifier;
}
