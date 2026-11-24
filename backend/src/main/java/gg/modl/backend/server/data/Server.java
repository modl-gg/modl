package gg.modl.backend.server.data;

import java.time.Instant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class Server {
    public static final String INSTANCE_ID = "instance";

    private final String serverName;

    private String id = INSTANCE_ID;

    @Nullable
    private String adminEmail;

    @Nullable
    @ToString.Exclude
    private String apiKey;

    @Nullable
    private String publicUrl;

    @Nullable
    private Instant createdAt;

    @Nullable
    private Instant updatedAt;

    @Nullable
    private Instant staffPermissionsUpdatedAt;

    @Nullable
    private Instant punishmentTypesUpdatedAt;

    @Nullable
    private SetupProgress setup;

    public boolean isClaimed() {
        return adminEmail != null && !adminEmail.isBlank();
    }

    public String claimedAdminEmail() {
        if (adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalStateException("The instance has no owner yet");
        }
        return adminEmail;
    }
}
