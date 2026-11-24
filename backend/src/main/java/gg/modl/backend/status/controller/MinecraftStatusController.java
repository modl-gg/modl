package gg.modl.backend.status.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.SupportReport;
import gg.modl.backend.status.SupportReportService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MinecraftStatusController {
    private final SupportReportService reports;

    @GetMapping(RESTMappingV1.MINECRAFT_STATUS)
    public StatusReportJson status() {
        SupportReport report = reports.report();
        return new StatusReportJson(report.facts().stream().map(StatusEntryJson::of).toList(), report.render(),
            report.generatedAt().toEpochMilli());
    }

    public record StatusReportJson(List<StatusEntryJson> entries, String report, long generatedAt) {
    }

    public record StatusEntryJson(String key, String section, String label, String value, String level) {
        static StatusEntryJson of(StatusFact fact) {
            return new StatusEntryJson(fact.item().key(), fact.item().section().title(), fact.item().label(), fact.value(),
                fact.level().name().toLowerCase(Locale.ROOT));
        }
    }
}
