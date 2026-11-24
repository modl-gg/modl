package gg.modl.backend.player.data.punishment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PunishmentIssuerKindTest {
    @Test
    void importedIssuersAreStaffUnlessTheyAreTheConsoleOrNamedByNobody() {
        assertThat(PunishmentIssuerKind.ofImported("staff-1", "CONSOLE")).isEqualTo(PunishmentIssuerKind.STAFF);
        assertThat(PunishmentIssuerKind.ofImported(null, " Console ")).isEqualTo(PunishmentIssuerKind.CONSOLE);
        assertThat(PunishmentIssuerKind.ofImported(null, "Notch")).isEqualTo(PunishmentIssuerKind.STAFF);
        assertThat(PunishmentIssuerKind.ofImported(null, " ")).isEqualTo(PunishmentIssuerKind.UNKNOWN);
        assertThat(PunishmentIssuerKind.ofImported(null, null)).isEqualTo(PunishmentIssuerKind.UNKNOWN);
    }

    @Test
    void anUnrecognisedStoredKindReadsAsUnknown() {
        assertThat(PunishmentIssuerKind.fromStored("AUTOMATED")).isEqualTo(PunishmentIssuerKind.AUTOMATED);
        assertThat(PunishmentIssuerKind.fromStored("robot")).isEqualTo(PunishmentIssuerKind.UNKNOWN);
        assertThat(PunishmentIssuerKind.fromStored(null)).isEqualTo(PunishmentIssuerKind.UNKNOWN);
    }
}
