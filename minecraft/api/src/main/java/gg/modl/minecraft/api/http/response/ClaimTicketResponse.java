package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter @NoArgsConstructor @AllArgsConstructor
public class ClaimTicketResponse extends StatusResponse {
    private String message;
    private @Nullable String ticketId, subject;
    private int status;
    private boolean success;
    private @Nullable String publicUrl;

    @Override
    public boolean isSuccess() {
        return success && super.isSuccess();
    }
}
