package gg.modl.backend.homepage.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HomepageCard {
    private String id;

    private String title;
    private @Nullable String description;
    private @Nullable String icon;

    private @Nullable String iconColor;

    private @Nullable String actionType;

    private @Nullable String actionUrl;

    private @Nullable String actionButtonText;

    private @Nullable String categoryId;

    private @Nullable String backgroundColor;

    private int ordinal;

    private boolean isEnabled;

    private Instant createdAt;

    private Instant updatedAt;
}
