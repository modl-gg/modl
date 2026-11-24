package gg.modl.backend.analytics.dto.response;

import java.util.List;

public record PlayerActivityResponse(
    List<DailyCount> newPlayersTrend,
    List<CountryCount> loginsByCountry,
    SuspiciousActivity suspiciousActivity
) {
    public record DailyCount(String date, int count) {}

    public record CountryCount(String country, int count) {}

    public record SuspiciousActivity(int vpnYesCount, int vpnNoCount, int vpnUnknownCount, int hostingCount) {}
}
