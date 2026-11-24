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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketNote {
    private String text;
    @Nullable
    private String issuerName;
    @Nullable
    private String issuerAvatar;
    private Instant date;
    @Builder.Default
    private List<Object> attachments = new ArrayList<>();
}
