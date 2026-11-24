package gg.modl.backend.audit.data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    private String id;

    private Instant created;
    private String level;
    private String source;
    private String description;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
