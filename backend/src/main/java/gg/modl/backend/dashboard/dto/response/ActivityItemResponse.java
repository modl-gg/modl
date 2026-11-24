package gg.modl.backend.dashboard.dto.response;

import java.time.Instant;
import java.util.List;

public record ActivityItemResponse(
    String id,
    String type,
    String color,
    String title,
    Instant time,
    String description,
    List<ActivityAction> actions
) {
    public record ActivityAction(
        String label,
        String link,
        boolean primary
    ) {
    }
}
