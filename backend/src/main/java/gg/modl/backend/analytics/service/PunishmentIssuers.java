package gg.modl.backend.analytics.service;

import gg.modl.backend.analytics.dto.response.PunishmentAnalyticsResponse;
import gg.modl.backend.player.repository.IssuerCount;
import gg.modl.backend.player.service.IssuerAttribution;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PunishmentIssuers {
    private static final Comparator<Tally> MOST_FIRST = Comparator
        .comparingLong(Tally::count).reversed()
        .thenComparing(tally -> tally.attribution().key());

    private PunishmentIssuers() {
    }

    static List<PunishmentAnalyticsResponse.StaffPunishment> top(
        List<IssuerCount> counts, Map<String, String> staffNamesById, int limit) {
        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (IssuerCount count : counts) {
            IssuerAttribution attribution = IssuerAttribution.of(count.kind(), count.issuerId(), count.issuerName(), staffNamesById);
            tallies.merge(attribution.key(), new Tally(attribution, count.count()), Tally::plus);
        }
        return tallies.values().stream()
            .sorted(MOST_FIRST)
            .limit(limit)
            .map(tally -> new PunishmentAnalyticsResponse.StaffPunishment(
                tally.attribution().kind(), tally.attribution().name(), Math.toIntExact(tally.count())))
            .toList();
    }

    private record Tally(IssuerAttribution attribution, long count) {
        Tally plus(Tally other) {
            return new Tally(attribution, count + other.count);
        }
    }
}
