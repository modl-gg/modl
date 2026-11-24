package gg.modl.backend.role.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffRole {
    private String id;

    private String name;

    private @Nullable String description;

    @Builder.Default
    private List<String> permissions = new ArrayList<>();

    @Builder.Default
    private boolean isDefault = false;

    @Builder.Default
    private int order = 999;

    private Instant createdAt;

    private Instant updatedAt;
}
