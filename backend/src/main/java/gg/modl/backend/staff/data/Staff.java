package gg.modl.backend.staff.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class Staff {
    public static final String DEFAULT_DATE_FORMAT = "MMM D, YYYY";

    private @Nullable String id;

    private String email;

    private String username;

    private @Nullable String roleId;

    private @Nullable String assignedMinecraftUuid;

    private @Nullable String assignedMinecraftUsername;

    @Builder.Default
    private String language = "en";

    @Builder.Default
    private String dateFormat = DEFAULT_DATE_FORMAT;

    private @Nullable TicketSubscriptionSettings ticketSubscriptionSettings;

    private @Nullable String twoFactorTokenHash;

    private @Nullable String twoFactorTokenIp;

    private @Nullable Long twoFactorTokenCreatedAt;

    @Builder.Default
    private boolean twoFactorPendingDelivery = false;

    private @Nullable String twoFactorSessionIp;

    private @Nullable Long twoFactorSessionExpiresAt;

    private @Nullable Instant lastSeen;

    private Instant createdAt;

    private Instant updatedAt;

    public String requireId() {
        if (id == null) {
            throw new IllegalStateException("The configured owner without a staff record has no staff id");
        }
        return id;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketSubscriptionSettings {
        @Builder.Default
        private boolean enabled = true;
        private @Nullable String frequency;
        private @Nullable NotificationSettings emailNotifications;
        private @Nullable NotificationSettings pushNotifications;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationSettings {
        @Builder.Default
        private boolean enabled = false;
        @Builder.Default
        private boolean newTickets = false;
        @Builder.Default
        private boolean ticketReplies = false;
        @Builder.Default
        private boolean ticketStatusChanges = false;
        @Builder.Default
        private boolean ticketAssignments = false;
        @Builder.Default
        private List<String> subscribedTypes = new ArrayList<>();
    }
}
