package gg.modl.minecraft.core.impl.commands.staff;

import com.google.gson.Gson;
import gg.modl.minecraft.api.http.response.StatusReportResponse;
import gg.modl.minecraft.core.boot.CoreState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PluginStatusReportTest {
    @Test
    void blankLinesOfTheBackendReportStayBlank() {
        StatusReportResponse backend = new Gson().fromJson("{\"report\":\"Database\\nEngine: sqlite\\n\\nStorage\\nUsed: 1 MB\\n\"}",
            StatusReportResponse.class);
        PluginStatusReport report = new PluginStatusReport("3.0.0", "Paper", "1.21.11", "21", CoreState.RUNNING, "");

        List<String> lines = report.withBackend(backend);

        assertFalse(lines.contains("-"));
        assertEquals("", lines.get(lines.indexOf("Engine: sqlite") + 1));
        assertEquals("Used: 1 MB", lines.get(lines.size() - 1));
        assertEquals("Panel URL: -", lines.get(5));
    }
}
