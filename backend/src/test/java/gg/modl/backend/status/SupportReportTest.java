package gg.modl.backend.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SupportReportTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void theReportListsSectionsInOrderWithMarkersForProblems() {
        SupportReportService service = new SupportReportService(List.of(
            contributor(StatusSection.DATABASE, StatusItem.DATABASE_TYPE.ok("SQLite (built in)")),
            contributor(StatusSection.INSTANCE, StatusItem.VERSION.ok("3.0.0"), StatusItem.SETUP.warning("unclaimed"))), CLOCK);

        assertThat(service.report().render()).isEqualTo("""
            modl support report
            Generated: 2026-11-22T12:00:00Z

            [Instance]
            Version: 3.0.0
            Setup: unclaimed (warning)

            [Database]
            Type: SQLite (built in)
            """);
    }

    @Test
    void aBrokenContributorShowsAsAProblemWithoutItsMessage() {
        StatusContributor broken = new StatusContributor() {
            @Override
            public StatusSection section() {
                return StatusSection.EMAIL;
            }

            @Override
            public List<StatusFact> facts() {
                throw new IllegalStateException("smtp://user:hunter2@mail.example.com refused");
            }
        };

        String report = new SupportReportService(List.of(broken), CLOCK).report().render();

        assertThat(report).contains("Delivery: could not be read (IllegalStateException) (problem)").doesNotContain("hunter2");
    }

    @Test
    void valuesCannotInjectExtraLines() {
        StatusFact fact = StatusItem.PANEL_URL.ok("https://panel.example.com\nAPI key: leaked");

        assertThat(fact.value()).doesNotContain("\n");
    }

    @Test
    void everyItemHasAUniqueStableKey() {
        Set<String> keys = Arrays.stream(StatusItem.values()).map(StatusItem::key).collect(Collectors.toSet());

        assertThat(keys).hasSize(StatusItem.values().length);
        assertThat(keys).allMatch(key -> key.matches("[a-z]+\\.[A-Za-z]+"));
    }

    private static StatusContributor contributor(StatusSection section, StatusFact... facts) {
        return new StatusContributor() {
            @Override
            public StatusSection section() {
                return section;
            }

            @Override
            public List<StatusFact> facts() {
                return List.of(facts);
            }
        };
    }
}
