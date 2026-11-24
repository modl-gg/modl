package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class StaffListResponse extends StatusResponse {
    private List<StaffEntry> staff;
    private int status;

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class StaffEntry {
        private String id, username, email, role, minecraftUuid, minecraftUsername, lastServer;
        private List<String> permissions;
        private @Nullable Instant lastSeen;
        private Long totalPlaytimeMs;
        private int punishmentsIssuedCount;
    }
}
