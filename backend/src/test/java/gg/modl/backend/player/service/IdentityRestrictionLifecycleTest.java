package gg.modl.backend.player.service;

import static gg.modl.backend.player.service.LifecycleHarness.NOW;
import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_NAME;
import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_UUID;
import static gg.modl.backend.player.service.PunishmentTypesFixture.NAME_RESTRICTED;
import static gg.modl.backend.player.service.PunishmentTypesFixture.SKIN_RESTRICTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.request.MinecraftCreatePunishmentRequest;
import gg.modl.backend.server.data.Server;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityRestrictionLifecycleTest {
    private static final int MANUAL_BAN = 2;
    private static final long DAY = 86_400_000L;

    private final Server server = mock(Server.class);
    private final LifecycleHarness harness = new LifecycleHarness();

    @Test
    void aNameRestrictionBansUntilTheNameChanges() {
        harness.lifecycle.createPunishment(server, PLAYER_UUID, request(NAME_RESTRICTED, null));

        Punishment issued = harness.inserted();
        assertThat(issued.data().enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
        assertThat(issued.data().blockedName()).isEqualTo(PLAYER_NAME);
        assertThat(issued.data().duration()).isNull();
    }

    @Test
    void thePluginsRegularSeverityAndLeftoverTermsNeverPutATimerOrOptionsOnARestriction() {
        Map<String, Object> data = new HashMap<>(Map.of("duration", DAY, "altBlocking", true, "wipeAfterExpiry", true));

        harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", null, NAME_RESTRICTED, "Offensive name", DAY, data, null, null, "regular", null, true));

        Punishment issued = harness.inserted();
        assertThat(issued.data().enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
        assertThat(issued.data().duration()).isNull();
        assertThat(issued.data().altBlocking()).isFalse();
        assertThat(issued.data().wipeAfterExpiry()).isFalse();
        assertThat(issued.data().reason()).isEqualTo("Offensive name");
    }

    @Test
    void aClientCannotChooseTheBlockedName() {
        harness.lifecycle.createPunishment(server, PLAYER_UUID, request(NAME_RESTRICTED, Map.of("blockedName", "SomeoneElse")));

        assertThat(harness.inserted().data().blockedName()).isEqualTo(PLAYER_NAME);
    }

    @Test
    void aSkinRestrictionWithoutAKnownSkinIsRefusedInsteadOfBanningForever() {
        assertThatThrownBy(() -> harness.lifecycle.createPunishment(server, PLAYER_UUID, request(SKIN_RESTRICTED, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("manual ban");

        assertThat(harness.inserted()).isNull();
        verify(harness.realtime, never()).punishmentIssued(any(), any());
    }

    @Test
    void aNameRestrictionOnAnUnknownNameIsRefused() {
        harness.player.getUsernames().clear();

        assertThatThrownBy(() -> harness.lifecycle.createPunishment(server, PLAYER_UUID, request(NAME_RESTRICTED, null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void aRestrictionBehindAnotherBanWaitsWithoutBeingPushedToThePlugin() {
        Punishment running = punishment("RUNNING1", MANUAL_BAN, new HashMap<>(Map.of("enforcementCategory", "BAN")));
        when(harness.punishments.findByPlayer(LifecycleHarness.PLAYER_ID)).thenReturn(List.of(running));

        harness.lifecycle.createPunishment(server, PLAYER_UUID, request(NAME_RESTRICTED, null));

        assertThat(harness.inserted().data().status()).isEqualTo(PunishmentStatus.UNSTARTED);
        verify(harness.realtime, never()).punishmentIssued(any(), any());
        verify(harness.realtime).punishmentQueued(eq(server), any());
    }

    @Test
    void aNewNameLiftsTheRestrictionAndRecordsWhy() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of("blockedName", PLAYER_NAME)));

        assertThat(lift("NewName", null)).containsExactly(restriction.getId());

        verify(harness.punishments).appendModification(eq(restriction.getId()), anyInt(),
            argThat(modification -> "SYSTEM_PARDON".equals(modification.type()) && modification.reason().contains("NewName")),
            any(), any());
    }

    @Test
    void aQueuedRestrictionLiftsToo() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of(
            "blockedName", PLAYER_NAME, "status", PunishmentStatus.UNSTARTED)));

        assertThat(lift("NewName", null)).containsExactly(restriction.getId());
    }

    @Test
    void aNewSkinLiftsASkinRestriction() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of("blockedSkin", "old-skin")));

        assertThat(lift(PLAYER_NAME, "new-skin")).containsExactly(restriction.getId());
    }

    @Test
    void theSameNameInAnotherCaseDoesNotLift() {
        storedRestriction(new HashMap<>(Map.of("blockedName", PLAYER_NAME)));

        assertThat(lift("currentname", null)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Unknown"})
    void anUnknownCurrentSkinNeverLifts(String currentSkin) {
        storedRestriction(new HashMap<>(Map.of("blockedSkin", "old-skin")));

        assertThat(lift(PLAYER_NAME, currentSkin)).isEmpty();
    }

    @Test
    void liftingAnImportedRestrictionWithAltBlockingLiftsItsLinkedBans() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of("blockedName", PLAYER_NAME, "altBlocking", true)));
        Punishment linked = punishment("LINKED01", Punishment.LINKED_BAN_TYPE_ORDINAL,
            new HashMap<>(Map.of("enforcementCategory", "BAN", "linkedBanId", restriction.getId())));
        when(harness.punishments.findLinkedBans(restriction.getId())).thenReturn(List.of(harness.store(linked)));

        lift("NewName", null);

        verify(harness.punishments).appendModification(eq(linked.getId()), anyInt(),
            argThat(modification -> "SYSTEM_PARDON".equals(modification.type())), any(), any());
    }

    @Test
    void deletingTheTypeDoesNotStrandTheBan() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of("blockedName", PLAYER_NAME)));
        harness.types.delete(NAME_RESTRICTED);

        assertThat(lift("NewName", null)).containsExactly(restriction.getId());
    }

    @Test
    void aRestrictionRefusesAltBlockingFromThePlugin() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of("blockedName", PLAYER_NAME)));

        assertThatThrownBy(() -> harness.mutations.toggleOption(server, restriction.getId(), "ALT_BLOCKING", true, "Mod", null, Shortenings.PERMITTED))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void aRejectionCarryingADurationDoesNotStartAQueuedRestriction() {
        Punishment restriction = storedRestriction(new HashMap<>(Map.of(
            "blockedName", PLAYER_NAME, "status", PunishmentStatus.UNSTARTED)));

        harness.mutations.addModification(server, PLAYER_UUID, restriction.getId(),
            new AddModificationRequest("APPEAL_REJECT", "Mod", null, DAY, "Upheld", null), Shortenings.PERMITTED);

        verify(harness.punishments, never()).markStarted(any(), any());
        verify(harness.punishments).appendModification(eq(restriction.getId()), anyInt(),
            argThat(modification -> modification.effectiveDuration() == null), any(), any());
    }

    private List<String> lift(String username, String skinHash) {
        return harness.lifecycle.checkRestrictionAutoPardons(server, harness.player, username, skinHash);
    }

    private Punishment storedRestriction(Map<String, Object> data) {
        data.put("enforcementCategory", "BAN");
        Punishment restriction = punishment("RESTRICT", NAME_RESTRICTED, data);
        harness.store(restriction);
        harness.player.setPunishments(new ArrayList<>(List.of(restriction)));
        when(harness.punishments.findEnforcementRelevant(eq(LifecycleHarness.PLAYER_ID), any())).thenReturn(List.of(restriction));
        when(harness.punishments.findByPlayer(LifecycleHarness.PLAYER_ID)).thenReturn(List.of(restriction));
        return restriction;
    }

    private static Punishment punishment(String id, int ordinal, Map<String, Object> data) {
        boolean queued = PunishmentStatus.UNSTARTED.equals(data.get("status"));
        return new Punishment(id, ordinal, "Mod", null, PunishmentIssuerKind.STAFF, NOW.minusSeconds(60), queued ? null : NOW.minusSeconds(60),
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), data);
    }

    private static CreatePunishmentRequest request(int ordinal, Map<String, Object> data) {
        return new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, ordinal, null, null, null, null, null, data, "Against the rules", null);
    }
}
