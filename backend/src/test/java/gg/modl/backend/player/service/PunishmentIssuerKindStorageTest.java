package gg.modl.backend.player.service;

import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.request.MinecraftCreatePunishmentRequest;
import gg.modl.backend.server.data.Server;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PunishmentIssuerKindStorageTest {
    private static final int MANUAL_BAN = 2;

    private final Server server = mock(Server.class);
    private final LifecycleHarness harness = new LifecycleHarness();

    @Test
    void aPunishmentFromTheServerConsoleIsStoredAsConsole() {
        harness.lifecycle.createMinecraftPunishment(server, minecraft(null, true));

        Punishment issued = harness.inserted();
        assertThat(issued.getIssuerKind()).isEqualTo(PunishmentIssuerKind.CONSOLE);
        assertThat(issued.getIssuerId()).isNull();
    }

    @Test
    void aPunishmentFromAStaffMemberInGameIsStoredAsStaff() {
        harness.lifecycle.createMinecraftPunishment(server, minecraft("staff-1", null));

        Punishment issued = harness.inserted();
        assertThat(issued.getIssuerKind()).isEqualTo(PunishmentIssuerKind.STAFF);
        assertThat(issued.getIssuerId()).isEqualTo("staff-1");
    }

    @Test
    void aPunishmentCreatedInThePanelIsStoredAsTheSignedInStaffMember() {
        CreatePunishmentRequest unattributed = new CreatePunishmentRequest(null, null, PunishmentIssuerKind.UNKNOWN, MANUAL_BAN, null,
            null, null, null, null, null, "Cheating", null);

        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, PunishmentIssuer.staff("Alice", "staff-2"), unattributed);

        Punishment issued = harness.inserted();
        assertThat(issued.getIssuerKind()).isEqualTo(PunishmentIssuerKind.STAFF);
        assertThat(issued.getIssuerId()).isEqualTo("staff-2");
    }

    private static MinecraftCreatePunishmentRequest minecraft(@Nullable String issuerId, @Nullable Boolean console) {
        return new MinecraftCreatePunishmentRequest(PLAYER_UUID.toString(), "Mod", issuerId, MANUAL_BAN, "Cheating", null, null, null,
            null, null, null, console);
    }
}
