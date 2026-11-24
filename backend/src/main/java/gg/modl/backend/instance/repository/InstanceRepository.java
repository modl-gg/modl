package gg.modl.backend.instance.repository;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Optional;

public interface InstanceRepository {
    Optional<Server> find();

    Server ensure(String serverName, Instant now);

    boolean claim(String adminEmail, @Nullable String publicUrl, Instant now);

    void updateAdminEmail(String adminEmail, Instant now);

    void assignConfiguredAdminEmail(String adminEmail, Instant now);

    boolean recordOwnerSignIn(Instant now);

    void updateSetupStep(SetupStep step, SetupStepStatus status, Instant now);

    void completeSetup(Instant now);

    void updateServerName(String serverName, Instant now);

    void updatePublicUrl(@Nullable String publicUrl, Instant now);

    boolean storePublicUrlIfUnset(String publicUrl, Instant now);

    void updateApiKey(String apiKey, Instant now);

    void updateStaffPermissionsTimestamp(Instant timestamp);

    void updatePunishmentTypesTimestamp(Instant timestamp);
}
