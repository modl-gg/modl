package gg.modl.minecraft.api.http.response;

import gg.modl.minecraft.api.SimplePunishment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Getter @NoArgsConstructor @AllArgsConstructor
public class SyncResponse {
    private String timestamp;
    private SyncData data;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SyncData {
        private List<PendingPunishment> pendingPunishments;
        private List<PendingPunishment> recentlyStartedPunishments;
        private List<ModifiedPunishment> recentlyModifiedPunishments;
        private List<PlayerNotification> playerNotifications;
        private List<ActiveStaffMember> activeStaffMembers;
        private List<StaffNotification> staffNotifications;
        private List<PendingStatWipe> pendingStatWipes;
        private List<Staff2faVerification> staff2faVerifications;
        private @Nullable Long staffPermissionsUpdatedAt, punishmentTypesUpdatedAt;
        private @Nullable String panelUrl;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class PendingPunishment {
        private String minecraftUuid, username;
        private SimplePunishment punishment;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class ModifiedPunishment {
        private String minecraftUuid, username;
        private PunishmentWithModifications punishment;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class PunishmentWithModifications {
        private String id;
        private List<PunishmentModification> modifications;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class PunishmentModification {
        private String type;
        private @Nullable Long timestamp, effectiveDuration;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class PlayerNotification {
        private String id, message, type;
        private @Nullable String targetPlayerUuid;
        private @Nullable Map<String, Object> data;
        private @Nullable Long timestamp;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class ActiveStaffMember {
        private String minecraftUuid, minecraftUsername, staffUsername, staffRole, email, staffId;
        private List<String> permissions;
        private @Nullable Boolean twoFactorSessionValid;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class StaffNotification {
        private String id, type, message;
        private @Nullable Map<String, Object> data;
        private @Nullable Long timestamp;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class PendingStatWipe {
        private String minecraftUuid, username, punishmentId;
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class Staff2faVerification {
        private String minecraftUuid;
        private @Nullable String ip;
    }
}
