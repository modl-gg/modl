package gg.modl.backend.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.analytics.dto.response.AuditLogsAnalyticsResponse;
import gg.modl.backend.analytics.dto.response.TicketAnalyticsResponse;
import gg.modl.backend.analytics.repository.AnalyticsRepository;
import gg.modl.backend.analytics.repository.CategoryResolution;
import gg.modl.backend.analytics.repository.TimeBucketCount;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsServiceTicketAnalyticsTest {
    private static final Instant NOW = Instant.parse("2026-03-15T12:30:00Z");

    private final Server server = TestServers.server("Server", "owner@example.com");
    private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
    private final AnalyticsService service = new AnalyticsService(repository, mock(PunishmentRepository.class),
        mock(PunishmentTypeService.class), mock(IssuerNameResolver.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void ticketAnalyticsComputesAverageResolutionHoursByCategoryAndOverallByTicket() {
        when(repository.averageResolutionByCategory(any())).thenReturn(List.of(
            new CategoryResolution("bug", 7_200_000.0, 3),
            new CategoryResolution("support", 1_800_000.0, 1)));

        TicketAnalyticsResponse response = service.getTicketAnalytics(server, "30d");

        assertThat(response.avgResolutionByCategory()).containsExactly(
            new TicketAnalyticsResponse.CategoryResolutionTime("Bug Report", 2.0),
            new TicketAnalyticsResponse.CategoryResolutionTime("General Support", 0.5));
        assertThat(response.avgResolutionHours()).isEqualTo(1.625);
    }

    @Test
    void withoutClosedTicketsTheOverallResolutionIsZero() {
        assertThat(service.getTicketAnalytics(server, "30d").avgResolutionHours()).isZero();
    }

    @Test
    void statusesAreCanonicalIds() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("open", 4L);
        byStatus.put("closed", 2L);
        byStatus.put("legacy", 1L);
        when(repository.countTicketsByStatus(any())).thenReturn(byStatus);

        assertThat(service.getTicketAnalytics(server, "30d").byStatus()).containsExactly(
            new TicketAnalyticsResponse.StatusCount("open", 4),
            new TicketAnalyticsResponse.StatusCount("closed", 2),
            new TicketAnalyticsResponse.StatusCount("other", 1));
    }

    @Test
    void periodsThatMeanTheSameWindowShareOneCachedReport() {
        service.getTicketAnalytics(server, "30d");
        service.getTicketAnalytics(server, "anything-else");

        verify(repository, times(1)).countTicketsByStatus(any());
    }

    @Test
    void aFailedReportKeepsItsExceptionTypeForTheHttpHandler() {
        when(repository.countTicketsByStatus(any())).thenThrow(new IllegalStateException("busy"));

        assertThatThrownBy(() -> service.getTicketAnalytics(server, "7d")).isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void theHourlyTrendHasTwentyFourHoursEndingWithTheCurrentOne() {
        Instant firstHour = Instant.parse("2026-03-14T13:00:00Z");
        when(repository.countAuditLogsByHour(firstHour))
            .thenReturn(List.of(new TimeBucketCount(Instant.parse("2026-03-15T12:00:00Z"), 4)));

        List<AuditLogsAnalyticsResponse.HourlyCount> trend = service.getAuditLogsAnalytics(server, "7d").hourlyTrend();

        assertThat(trend).hasSize(24);
        assertThat(trend.getFirst()).isEqualTo(new AuditLogsAnalyticsResponse.HourlyCount("13:00", 0));
        assertThat(trend.getLast()).isEqualTo(new AuditLogsAnalyticsResponse.HourlyCount("12:00", 4));
    }
}
