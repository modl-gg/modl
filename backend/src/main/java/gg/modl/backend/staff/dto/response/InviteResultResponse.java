package gg.modl.backend.staff.dto.response;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record InviteResultResponse(
    String message,
    List<String> success,
    List<FailedInvite> failed,
    List<InviteLink> inviteLinks
) {
    public record FailedInvite(
        String email,
        @Nullable String reason
    ) {
    }

    public record InviteLink(
        String email,
        String url
    ) {
    }
}
