package gg.modl.backend.database.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class TransferReportTest {
    @Test
    void everyTableIsComparedOnItsOwn() {
        TransferReport report = new TransferReport(new TreeMap<>(Map.of("modl_players", 12L, "modl_tickets", 3L, "modl_notes", 4L)));

        assertThat(report.tables()).isEqualTo(3);
        assertThat(report.rows()).isEqualTo(19);
        assertThat(report.mismatchesWith(Map.of("modl_players", 12L, "modl_tickets", 3L, "modl_notes", 4L))).isEmpty();
        assertThat(report.mismatchesWith(Map.of("modl_players", 13L, "modl_tickets", 2L, "modl_extra", 1L)))
            .containsExactly(
                new RowCountMismatch("modl_extra", 0, 1),
                new RowCountMismatch("modl_notes", 4, 0),
                new RowCountMismatch("modl_players", 12, 13),
                new RowCountMismatch("modl_tickets", 3, 2));
        assertThat(new RowCountMismatch("modl_players", 12, 10).describe()).isEqualTo("modl_players 10 of 12 rows");
    }
}
