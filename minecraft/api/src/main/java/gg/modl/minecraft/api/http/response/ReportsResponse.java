package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class ReportsResponse extends StatusResponse {
    private List<Report> reports;
    private int status;

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class Report {
        private String id, type, category, reporterName, reporterUuid, reportedPlayerUuid, reportedPlayerName,
                subject, content, status, priority;
        private @Nullable Instant createdAt;
        private List<String> assignedTo;
        private List<Object> chatMessages;
    }
}
