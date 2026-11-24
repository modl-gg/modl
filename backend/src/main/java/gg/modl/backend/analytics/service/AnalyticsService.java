package gg.modl.backend.analytics.service;

import gg.modl.backend.analytics.dto.response.AuditLogsAnalyticsResponse;
import gg.modl.backend.analytics.dto.response.PlayerActivityResponse;
import gg.modl.backend.analytics.dto.response.PunishmentAnalyticsResponse;
import gg.modl.backend.analytics.dto.response.TicketAnalyticsResponse;
import gg.modl.backend.analytics.repository.AnalyticsRepository;
import gg.modl.backend.analytics.repository.CategoryResolution;
import gg.modl.backend.analytics.repository.PlayerActivity;
import gg.modl.backend.analytics.repository.PunishmentBreakdown;
import gg.modl.backend.analytics.repository.TimeBucketCount;
import gg.modl.backend.player.repository.IssuerCount;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsService {
    private static final double MILLIS_PER_HOUR = 1000.0 * 60 * 60;
    private static final int TREND_HOURS = 24;
    private static final int TOP_ISSUERS = 20;
    private static final String OTHER = "Other";
    private static final String UNRECOGNISED_STATUS = "other";

    private final AnalyticsRepository analyticsRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final IssuerNameResolver issuerNameResolver;
    private final Clock clock;

    private final ReportResultCache<TicketAnalyticsResponse> ticketAnalyticsCache = new ReportResultCache<>();
    private final ReportResultCache<PunishmentAnalyticsResponse> punishmentAnalyticsCache = new ReportResultCache<>();
    private final ReportResultCache<AuditLogsAnalyticsResponse> auditLogsAnalyticsCache = new ReportResultCache<>();
    private final ReportResultCache<PlayerActivityResponse> playerActivityCache = new ReportResultCache<>();

    public TicketAnalyticsResponse getTicketAnalytics(Server server, String period) {
        ReportPeriod reportPeriod = ReportPeriod.parse(period);
        return ticketAnalyticsCache.get(reportPeriod, () -> computeTicketAnalytics(reportPeriod));
    }

    private TicketAnalyticsResponse computeTicketAnalytics(ReportPeriod period) {
        Instant start = period.startingBefore(clock.instant());
        List<TicketAnalyticsResponse.StatusCount> byStatus = analyticsRepository.countTicketsByStatus(start)
            .entrySet().stream()
            .map(entry -> new TicketAnalyticsResponse.StatusCount(statusId(entry.getKey()), Math.toIntExact(entry.getValue())))
            .toList();
        List<TicketAnalyticsResponse.CategoryCount> byCategory = analyticsRepository.countTicketsByCategory(start)
            .entrySet().stream()
            .map(entry -> new TicketAnalyticsResponse.CategoryCount(categoryName(entry.getKey()), Math.toIntExact(entry.getValue())))
            .toList();
        List<TicketAnalyticsResponse.DailyTicket> daily = analyticsRepository.countTicketsByDay(start).stream()
            .map(bucket -> new TicketAnalyticsResponse.DailyTicket(dateOf(bucket), countOf(bucket)))
            .toList();
        List<CategoryResolution> resolutions = analyticsRepository.averageResolutionByCategory(start);
        List<TicketAnalyticsResponse.CategoryResolutionTime> resolution = resolutions.stream()
            .map(entry -> new TicketAnalyticsResponse.CategoryResolutionTime(
                categoryName(entry.category()), entry.averageMillis() / MILLIS_PER_HOUR))
            .toList();
        return new TicketAnalyticsResponse(byStatus, byCategory, resolution, daily, overallResolutionHours(resolutions));
    }

    private static double overallResolutionHours(List<CategoryResolution> resolutions) {
        long closed = resolutions.stream().mapToLong(CategoryResolution::closedTickets).sum();
        if (closed == 0) {
            return 0;
        }
        double totalMillis = resolutions.stream()
            .mapToDouble(entry -> entry.averageMillis() * entry.closedTickets())
            .sum();
        return totalMillis / closed / MILLIS_PER_HOUR;
    }

    private static String categoryName(String category) {
        try {
            return TicketCategory.fromCanonicalId(category).getDisplayName();
        } catch (IllegalArgumentException unrecognised) {
            return OTHER;
        }
    }

    private static String statusId(String status) {
        try {
            return TicketStatus.fromCanonicalId(status).getId();
        } catch (IllegalArgumentException unrecognised) {
            return UNRECOGNISED_STATUS;
        }
    }

    public PunishmentAnalyticsResponse getPunishmentAnalytics(Server server, String period) {
        ReportPeriod reportPeriod = ReportPeriod.parse(period);
        return punishmentAnalyticsCache.get(reportPeriod, () -> computePunishmentAnalytics(server, reportPeriod));
    }

    private PunishmentAnalyticsResponse computePunishmentAnalytics(Server server, ReportPeriod period) {
        Instant start = period.startingBefore(clock.instant());
        PunishmentBreakdown breakdown = analyticsRepository.loadPunishmentBreakdown(start);
        List<PunishmentAnalyticsResponse.TypeCount> byType = breakdown.byTypeOrdinal().entrySet().stream()
            .map(entry -> new PunishmentAnalyticsResponse.TypeCount(
                punishmentTypeService.getPunishmentTypeName(server, entry.getKey()), Math.toIntExact(entry.getValue())))
            .sorted(Comparator.comparingInt(PunishmentAnalyticsResponse.TypeCount::count).reversed())
            .toList();
        List<PunishmentAnalyticsResponse.DailyPunishment> daily = breakdown.daily().stream()
            .map(bucket -> new PunishmentAnalyticsResponse.DailyPunishment(dateOf(bucket), countOf(bucket)))
            .toList();
        return new PunishmentAnalyticsResponse(byType, daily, topIssuers(server, punishmentRepository.countByIssuer(start)));
    }

    private List<PunishmentAnalyticsResponse.StaffPunishment> topIssuers(Server server, List<IssuerCount> counts) {
        Set<String> staffIds = counts.stream()
            .map(IssuerCount::issuerId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        return PunishmentIssuers.top(counts, issuerNameResolver.batchResolve(staffIds, server), TOP_ISSUERS);
    }

    public AuditLogsAnalyticsResponse getAuditLogsAnalytics(Server server, String period) {
        ReportPeriod reportPeriod = ReportPeriod.parse(period);
        return auditLogsAnalyticsCache.get(reportPeriod, () -> computeAuditLogsAnalytics(reportPeriod));
    }

    private AuditLogsAnalyticsResponse computeAuditLogsAnalytics(ReportPeriod period) {
        Instant now = clock.instant();
        List<AuditLogsAnalyticsResponse.LevelCount> byLevel =
            analyticsRepository.countAuditLogsByLevel(period.startingBefore(now)).entrySet().stream()
                .map(entry -> new AuditLogsAnalyticsResponse.LevelCount(entry.getKey(), Math.toIntExact(entry.getValue())))
                .toList();
        return new AuditLogsAnalyticsResponse(byLevel, hourlyTrend(now));
    }

    private List<AuditLogsAnalyticsResponse.HourlyCount> hourlyTrend(Instant now) {
        Instant firstHour = now.truncatedTo(ChronoUnit.HOURS).minus(TREND_HOURS - 1, ChronoUnit.HOURS);
        Map<Instant, Integer> countsByHour = analyticsRepository.countAuditLogsByHour(firstHour).stream()
            .collect(Collectors.toMap(TimeBucketCount::bucketStart, AnalyticsService::countOf));
        List<AuditLogsAnalyticsResponse.HourlyCount> trend = new ArrayList<>(TREND_HOURS);
        for (int offset = 0; offset < TREND_HOURS; offset++) {
            Instant hour = firstHour.plus(offset, ChronoUnit.HOURS);
            String label = String.format(Locale.ROOT, "%02d:00", hour.atZone(ZoneOffset.UTC).getHour());
            trend.add(new AuditLogsAnalyticsResponse.HourlyCount(label, countsByHour.getOrDefault(hour, 0)));
        }
        return trend;
    }

    public PlayerActivityResponse getPlayerActivityAnalytics(Server server, String period) {
        ReportPeriod reportPeriod = ReportPeriod.parse(period);
        return playerActivityCache.get(reportPeriod, () -> computePlayerActivity(reportPeriod));
    }

    private PlayerActivityResponse computePlayerActivity(ReportPeriod period) {
        PlayerActivity activity = analyticsRepository.loadPlayerActivity(period.startingBefore(clock.instant()));
        List<PlayerActivityResponse.DailyCount> newPlayers = activity.newPlayers().stream()
            .map(bucket -> new PlayerActivityResponse.DailyCount(dateOf(bucket), countOf(bucket)))
            .toList();
        List<PlayerActivityResponse.CountryCount> byCountry = activity.byCountry().entrySet().stream()
            .map(entry -> new PlayerActivityResponse.CountryCount(entry.getKey(), Math.toIntExact(entry.getValue())))
            .toList();
        return new PlayerActivityResponse(newPlayers, byCountry, new PlayerActivityResponse.SuspiciousActivity(
            Math.toIntExact(activity.vpn().yes()), Math.toIntExact(activity.vpn().no()),
            Math.toIntExact(activity.vpn().unknown()), Math.toIntExact(activity.hostingCount())));
    }

    private static String dateOf(TimeBucketCount bucket) {
        return LocalDate.ofInstant(bucket.bucketStart(), ZoneOffset.UTC).toString();
    }

    private static int countOf(TimeBucketCount bucket) {
        return Math.toIntExact(bucket.count());
    }
}
