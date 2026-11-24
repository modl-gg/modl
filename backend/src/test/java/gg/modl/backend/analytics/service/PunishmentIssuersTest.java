package gg.modl.backend.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.analytics.dto.response.PunishmentAnalyticsResponse.StaffPunishment;
import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.repository.IssuerCount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PunishmentIssuersTest {
    @Test
    void issuersAreAttributedByTheirStoredKindAndMergedPerIssuer() {
        List<IssuerCount> counts = List.of(
            new IssuerCount(PunishmentIssuerKind.STAFF, "staff-a", null, 4),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "alice", 1),
            new IssuerCount(PunishmentIssuerKind.CONSOLE, null, "Server", 3),
            new IssuerCount(PunishmentIssuerKind.CONSOLE, null, null, 3),
            new IssuerCount(PunishmentIssuerKind.STAFF, "removed-staff", null, 2),
            new IssuerCount(PunishmentIssuerKind.STAFF, "removed-staff", "Notch", 1),
            new IssuerCount(PunishmentIssuerKind.AUTOMATED, null, "AI Moderator", 1),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "Console", 1));

        assertThat(PunishmentIssuers.top(counts, Map.of("staff-a", "Alice"), 10)).containsExactly(
            new StaffPunishment(PunishmentIssuerKind.CONSOLE, "", 6),
            new StaffPunishment(PunishmentIssuerKind.STAFF, "Alice", 5),
            new StaffPunishment(PunishmentIssuerKind.UNKNOWN, "", 2),
            new StaffPunishment(PunishmentIssuerKind.AUTOMATED, "AI Moderator", 1),
            new StaffPunishment(PunishmentIssuerKind.STAFF, "Console", 1),
            new StaffPunishment(PunishmentIssuerKind.STAFF, "Notch", 1));
    }

    @Test
    void onlyTheTopIssuersAreReturned() {
        List<IssuerCount> counts = List.of(
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "a", 3),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "b", 2),
            new IssuerCount(PunishmentIssuerKind.STAFF, null, "c", 1));

        assertThat(PunishmentIssuers.top(counts, Map.of(), 2)).extracting(StaffPunishment::username).containsExactly("a", "b");
    }
}
