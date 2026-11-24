package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter @NoArgsConstructor @AllArgsConstructor
public class CreateTicketResponse {
    private @Nullable String ticketId, message;
    private @Nullable TicketInfo ticket;
    private boolean success;
    private @Nullable String publicUrl;

    @Getter @NoArgsConstructor
    public static class TicketInfo {
        private String id, type;
        private @Nullable String subject, status, created;
    }
}
