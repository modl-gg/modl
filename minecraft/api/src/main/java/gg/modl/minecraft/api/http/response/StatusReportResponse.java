package gg.modl.minecraft.api.http.response;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
public class StatusReportResponse {
    private List<Entry> entries = new ArrayList<>();
    private String report = "";
    private long generatedAt;

    @Getter
    @NoArgsConstructor
    public static class Entry {
        private String key;
        private String section;
        private String label;
        private String value;
        private String level;
    }
}
