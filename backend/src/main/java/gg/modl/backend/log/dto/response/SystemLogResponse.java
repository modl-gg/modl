package gg.modl.backend.log.dto.response;

import java.time.Instant;

public record SystemLogResponse(
    String id,
    String description,
    String level,
    String source,
    Instant created
) {}
