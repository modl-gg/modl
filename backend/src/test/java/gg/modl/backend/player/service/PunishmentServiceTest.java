package gg.modl.backend.player.service;

import static gg.modl.backend.player.PunishmentStore.BAN;
import static gg.modl.backend.player.PunishmentStore.change;
import static gg.modl.backend.player.PunishmentStore.draft;
import static gg.modl.backend.player.service.LifecycleHarness.NOW;
import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_ID;
import static gg.modl.backend.player.service.LifecycleHarness.PLAYER_UUID;
import static gg.modl.backend.player.service.PunishmentTypesFixture.CONFIGURED;
import static gg.modl.backend.player.service.PunishmentTypesFixture.SINGLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.dto.request.CreateEvidenceRequest;
import gg.modl.backend.player.dto.request.CreateNoteRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.request.MinecraftCreatePunishmentRequest;
import gg.modl.backend.player.dto.request.ModifyPunishmentTicketsRequest;
import gg.modl.backend.player.repository.PunishmentPatch;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.webhook.WebhookEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class PunishmentServiceTest {
    private static final long HOUR = 3_600_000L;
    private static final long OVER_LIMIT = RequestValidationLimits.PUNISHMENT_DURATION_MAX_MS + 1;
    private static final PunishmentIssuer SESSION_ISSUER = PunishmentIssuer.staff("SessionStaff", "staff-session");

    private final LifecycleHarness harness = new LifecycleHarness();
    private final Server server = TestServers.server("server", "admin@example.com");

    @Test
    void acknowledgementStartsThePunishmentAndClearsItsStatus() {
        harness.store(draft("PUNISH01", BAN, HOUR, null, PunishmentStatus.UNSTARTED));
        when(harness.punishments.markStarted("PUNISH01", NOW)).thenReturn(true);

        PunishmentOperationResult result = harness.lifecycle.acknowledgePunishment(server, PLAYER_UUID, "PUNISH01", true);

        assertThat(result.status()).isEqualTo(PunishmentOperationStatus.SUCCESS);
        assertThat(result.message()).isEqualTo("Punishment acknowledged");
    }

    @Test
    void aSecondAcknowledgementIsANoOp() {
        harness.store(draft("PUNISH01", BAN, HOUR, NOW, null));

        PunishmentOperationResult result = harness.lifecycle.acknowledgePunishment(server, PLAYER_UUID, "PUNISH01", true);

        assertThat(result.status()).isEqualTo(PunishmentOperationStatus.NO_OP);
        assertThat(result.message()).isEqualTo("Punishment already acknowledged");
    }

    @Test
    void toggleRejectsAnUnknownOption() {
        PunishmentOperationResult result = harness.mutations.toggleOption(server, "PUNISH01", "UNKNOWN", true, "Mod", null, Shortenings.PERMITTED);

        assertThat(result.status()).isEqualTo(PunishmentOperationStatus.INVALID_REQUEST);
        assertThat(result.message()).isEqualTo("Invalid option");
        verifyNoInteractions(harness.punishments);
    }

    @Test
    void aCollidingShortIdIsRegenerated() {
        doReturn(false)
            .doAnswer(call -> {
                harness.store(call.getArgument(1));
                return true;
            })
            .when(harness.punishments).insertIfAbsent(anyString(), any(), any());

        String id = harness.lifecycle.createPunishment(server, PLAYER_UUID, request(BAN, null, null, null));

        verify(harness.punishments, times(2)).insertIfAbsent(eq(PLAYER_ID), any(), any());
        assertThat(id).matches("[A-Z]{8}");
    }

    @Test
    void thePanelIssuerSignsThePunishmentItsNotesAndItsEvidence() {
        CreatePunishmentRequest forged = new CreatePunishmentRequest("Owner", "staff-victim", PunishmentIssuerKind.STAFF, BAN,
            List.of(new CreateNoteRequest("a note")),
            List.of(new CreateEvidenceRequest("proof", "text", null, null, null, null)),
            null, null, null, null, "Cheating", null);

        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER, forged);

        Punishment created = harness.inserted();
        assertThat(created.getIssuerId()).isEqualTo("staff-session");
        assertThat(created.getIssuerName()).isNull();
        assertThat(created.data().reason()).isEqualTo("Cheating");
        assertThat(created.getNotes()).extracting(PunishmentNote::text).doesNotContain("Cheating");
        assertThat(created.getNotes()).hasSize(1).allSatisfy(note -> {
            assertThat(note.issuerId()).isEqualTo("staff-session");
            assertThat(note.issuerName()).isNull();
        });
        assertThat(created.getEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.uploadedById()).isEqualTo("staff-session");
            assertThat(evidence.uploadedBy()).isNull();
        });
    }

    @Test
    void anIssuerWithoutAStaffRecordStoresTheSessionName() {
        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, PunishmentIssuer.staff("Admin", null),
            new CreatePunishmentRequest("Mallory", "staff-victim", PunishmentIssuerKind.STAFF, BAN, null, null, null, null, null, null, "Cheating", null));

        assertThat(harness.inserted().getIssuerId()).isNull();
        assertThat(harness.inserted().getIssuerName()).isEqualTo("Admin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unstarted", "Pardoned", "active", "a status that is far longer than the column"})
    void noClientCanSetAStatusThatIsNotAnOffenseLevel(String status) {
        CreatePunishmentRequest forged = new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, BAN, null, null, null, null, status,
            Map.of("status", status), "Cheating", null);

        harness.lifecycle.createPunishment(server, PLAYER_UUID, forged);
        assertThat(harness.inserted().data().status()).isNull();

        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER, forged);
        assertThat(harness.inserted().data().status()).isNull();
        assertThat(PunishmentState.activeUntil(harness.inserted())).isEqualTo(PunishmentState.MAX);
    }

    @Test
    void thePanelCannotMarkAPunishmentAiGeneratedOrPending() {
        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER,
            request(BAN, null, Map.of("aiGenerated", true, "pendingAcknowledgement", true, "silent", true), null));

        assertThat(harness.inserted().data().asMap())
            .doesNotContainKeys("aiGenerated", "pendingAcknowledgement")
            .containsEntry("silent", true);
        assertThat(harness.inserted().data().status()).isNull();
    }

    @Test
    void withoutASelectionTheBackendDerivesTheLevelDurationAndStatus() {
        CreatePunishmentRequest nothingSelected = new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, SINGLE, null, null, null, null,
            null, null, "Bad skin", null);

        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER, nothingSelected);

        assertThat(harness.inserted().data().severity()).isEqualTo("single");
        assertThat(harness.inserted().data().offenseLevel()).isEqualTo("first");
        assertThat(harness.inserted().data().status()).isEqualTo("low");
        assertThat(harness.inserted().data().duration()).isEqualTo(PunishmentTypesFixture.SINGLE_FIRST);
    }

    @Test
    void aDerivedOffenseLevelSurvivesTheQueueStatus() {
        when(harness.punishments.findByPlayer(PLAYER_ID)).thenReturn(List.of(banned("EXISTING")));

        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER, request(CONFIGURED, "severe", null, null));

        assertThat(harness.inserted().data().offenseLevel()).isEqualTo("first");
        assertThat(harness.inserted().data().duration()).isEqualTo(PunishmentTypesFixture.SEVERE_FIRST);
        assertThat(harness.inserted().data().status()).isEqualTo(PunishmentStatus.UNSTARTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "lenient", "regular", "normal", "severe", "aggravated", "single", "LOW", "Lenient"})
    void everySeverityARealClientSendsIsAccepted(String severity) {
        harness.lifecycle.createPunishment(server, PLAYER_UUID, request(CONFIGURED, severity, null, null));

        assertThat(harness.inserted().data().severity()).isEqualTo(severity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "high", "critical", "lenient ", "severe; the player repeatedly ignored several warnings"})
    void anUnknownSeverityFromABodyAStructOrAnAiAnswerIsRejected(String severity) {
        assertInvalid(request(CONFIGURED, severity, null, null));
        assertInvalid(request(CONFIGURED, null, Map.of("severity", severity), null));
        verifyNoInteractions(harness.punishments);
    }

    @Test
    void aNonTextSeverityIsRejected() {
        assertInvalid(request(CONFIGURED, null, Map.of("severity", 3.0), null));
    }

    @Test
    void linkedBanIdMustBeTheBareIdOfAnExistingPunishment() {
        harness.store(draft("PARENTID", BAN, null, NOW, null));

        for (Object invalid : List.<Object>of("PARENTID - Steve", "UNKNOWN1", 7.0)) {
            assertThatThrownBy(() -> harness.lifecycle.createPunishment(server, PLAYER_UUID,
                request(Punishment.LINKED_BAN_TYPE_ORDINAL, null, Map.of("linkedBanId", invalid), null)))
                .isInstanceOf(ValidationException.class);
        }
        harness.lifecycle.createPunishment(server, PLAYER_UUID,
            request(Punishment.LINKED_BAN_TYPE_ORDINAL, null, Map.of("linkedBanId", "PARENTID"), null));

        assertThat(harness.inserted().data().linkedBanId()).isEqualTo("PARENTID");
    }

    @Test
    void aBlankLinkedBanIdMeansNoLink() {
        harness.lifecycle.createPunishment(server, PLAYER_UUID, request(BAN, null, Map.of("linkedBanId", " "), null));

        assertThat(harness.inserted().data().linkedBanId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -86_400_000L, Long.MIN_VALUE})
    void zeroAndNegativeCreationDurationsArePermanent(long duration) {
        harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER, request(CONFIGURED, "severe", null, duration));

        Punishment created = harness.inserted();
        assertThat(PunishmentState.activeUntil(created)).isEqualTo(PunishmentState.MAX);
        assertThat(Optional.ofNullable(created.data().duration()))
            .isEqualTo(duration == 0 ? Optional.empty() : Optional.of(duration));
        assertThat(created.data().reason()).isEqualTo("Rule broken");
        assertThat(created.getNotes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -86_400_000L})
    void zeroAndNegativeDurationChangesArePermanent(long duration) {
        harness.store(draft("PUNISH01", BAN, HOUR, NOW, null));

        assertThat(harness.mutations.changeDuration(server, "PUNISH01", duration, "Mod", null, Shortenings.PERMITTED).success()).isTrue();

        ArgumentCaptor<PunishmentModification> modification = ArgumentCaptor.forClass(PunishmentModification.class);
        verify(harness.punishments).appendModification(eq("PUNISH01"), eq(4), modification.capture(),
            eq(PunishmentPatch.builder().duration(duration).build()), any());
        Punishment changed = draft("PUNISH01", BAN, HOUR, NOW, null);
        changed.getModifications().add(modification.getValue());
        assertThat(PunishmentState.activeUntil(changed)).isEqualTo(PunishmentState.MAX);
    }

    @Test
    void durationAloneCannotShortenOnAnyRoute() {
        harness.store(draft("PUNISH01", BAN, HOUR, NOW, null));

        assertThatThrownBy(() -> harness.mutations.changeDuration(server, "PUNISH01", 60_000L, "Mod", null, Shortenings.REFUSED))
            .isInstanceOfSatisfying(ForbiddenException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(PunishmentShortening.NEEDS_PARDON));
        assertThatThrownBy(() -> harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest("APPEAL_DURATION_CHANGE", "Mod", null, 60_000L, null, "APPEAL-1"), Shortenings.REFUSED))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest("MANUAL_DURATION_CHANGE", "Mod", null, 60_000L, null, null), Shortenings.REFUSED))
            .isInstanceOf(ForbiddenException.class);
        verify(harness.punishments, never()).appendModification(anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void durationAloneMayLengthen() {
        harness.store(draft("PUNISH01", BAN, HOUR, NOW, null));

        assertThat(harness.mutations.changeDuration(server, "PUNISH01", 2 * HOUR, "Mod", null, Shortenings.REFUSED).success())
            .isTrue();
        assertThat(harness.mutations.changeDuration(server, "PUNISH01", null, "Mod", null, Shortenings.REFUSED).success())
            .isTrue();
    }

    @Test
    void aMissingNewDurationIsStoredAsMinusOne() {
        harness.store(draft("PUNISH01", BAN, HOUR, NOW, null));

        harness.mutations.changeDuration(server, "PUNISH01", null, "Mod", null, Shortenings.PERMITTED);

        verify(harness.punishments).appendModification(eq("PUNISH01"), eq(4),
            argThat(modification -> modification.effectiveDuration() == -1L),
            eq(PunishmentPatch.builder().duration(-1L).build()), any());
    }

    @Test
    void theThousandYearBoundHoldsOnEveryRouteWithoutBeanValidation() {
        assertInvalid(request(BAN, null, null, OVER_LIMIT));
        assertInvalid(request(BAN, null, Map.of("duration", (double) OVER_LIMIT * 2), null));
        assertThatThrownBy(() -> harness.lifecycle.createPanelPunishment(server, PLAYER_UUID, SESSION_ISSUER,
            request(BAN, null, null, OVER_LIMIT))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", null, BAN, "Reason", OVER_LIMIT, null, null, null, null, null, true)))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> harness.mutations.changeDuration(server, "PUNISH01", OVER_LIMIT, "Mod", null, Shortenings.PERMITTED))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest("MANUAL_DURATION_CHANGE", "Mod", null, OVER_LIMIT, null, null), Shortenings.PERMITTED))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> harness.mutations.applyAppealApproval(server, PLAYER_UUID.toString(), "PUNISH01",
            change("M1", "APPEAL_DURATION_CHANGE", NOW, OVER_LIMIT), null, "APPEAL-1"))
            .isInstanceOf(ValidationException.class);
        verifyNoInteractions(harness.punishments);
    }

    @Test
    void aPluginPunishmentWithoutAnIssuerIsRefusedInsteadOfTrustedAsConsole() {
        assertThatThrownBy(() -> harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", null, BAN, "Reason", null, null, null, null, null, null, null)))
            .isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(MinecraftIssuer.ISSUER_REQUIRED));
        verifyNoInteractions(harness.punishments, harness.issuerAuthorizer);
    }

    @Test
    void aPluginPunishmentIsAuthorizedForItsTypeAndRecordsTheStaffMember() {
        harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", " staff-1 ", BAN, "Reason", null, null, List.of("note"), null, null, null, false));

        verify(harness.issuerAuthorizer).requirePermission(eq(server),
            argThat(issuer -> "staff-1".equals(issuer.staffId())), eq(PermissionService.punishmentApplyPermissionId(BAN)));
        assertThat(harness.inserted().getIssuerId()).isEqualTo("staff-1");
    }

    @Test
    void aPluginPunishmentRefusedByTheAuthorizerWritesNothing() {
        doThrow(new ForbiddenException("You do not have permission to do this"))
            .when(harness.issuerAuthorizer).requirePermission(any(), any(), anyString());

        assertThatThrownBy(() -> harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", "staff-1", BAN, "Reason", null, null, null, null, null, null, false)))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(harness.punishments);
    }

    @Test
    void aPluginPunishmentWaitsForItsAcknowledgement() {
        harness.lifecycle.createMinecraftPunishment(server, new MinecraftCreatePunishmentRequest(
            PLAYER_UUID.toString(), "Mod", null, BAN, "Reason", null, null, null, null, null, null, true));

        assertThat(harness.inserted().data().status()).isEqualTo(PunishmentStatus.UNSTARTED);
        assertThat(harness.inserted().getStarted()).isNull();
        assertThat(harness.inserted().data().asMap()).doesNotContainKey("pendingAcknowledgement");
    }

    @Test
    void creationClosesTicketsInOneUnitAndPublishesAfterIt() {
        doAnswer(call -> {
            assertThat(harness.unitOfWork.inWrite()).isTrue();
            return null;
        }).when(harness.tickets).closeTicketForPunishment(any(), anyString(), anyString());
        doAnswer(call -> {
            assertThat(harness.unitOfWork.inWrite()).isFalse();
            return null;
        }).when(harness.realtime).punishmentIssued(any(), any());

        harness.lifecycle.createPunishment(server, PLAYER_UUID, new CreatePunishmentRequest(
            "Mod", null, PunishmentIssuerKind.STAFF, BAN, null, null, List.of("T-2", "T-1"), null, null, null, "Cheating", null));

        assertThat(harness.unitOfWork.committedWrites()).isEqualTo(1);
        verify(harness.tickets).closeTicketForPunishment(server, "T-1", "Mod");
        verify(harness.tickets).closeTicketForPunishment(server, "T-2", "Mod");
        verify(harness.realtime).punishmentIssued(eq(server), any());
        verify(harness.webhooks).publish(eq(WebhookEvent.NEW_PUNISHMENTS), any());
    }

    @Test
    void aFailedTicketCloseFailsTheCreationAndPublishesNothing() {
        doThrow(new IllegalStateException("write failed"))
            .when(harness.tickets).closeTicketForPunishment(any(), anyString(), anyString());

        assertThatThrownBy(() -> harness.lifecycle.createPunishment(server, PLAYER_UUID, new CreatePunishmentRequest(
            "Mod", null, PunishmentIssuerKind.STAFF, BAN, null, null, List.of("T-1"), null, null, null, "Cheating", null)))
            .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(harness.realtime, harness.webhooks);
        assertThat(harness.unitOfWork.committedWrites()).isZero();
    }

    @Test
    void evidenceStoredOnThisServerIsMarkedAttached() {
        String url = "/storage/evidence/proof.png";
        when(harness.urls.keyOf(url)).thenReturn(Optional.of("evidence/proof.png"));

        harness.lifecycle.createPunishment(server, PLAYER_UUID, new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, BAN, null,
            List.of(new CreateEvidenceRequest("proof.png", "file", url, "proof.png", "image/png", 12L)),
            null, null, null, null, "Cheating", null));

        verify(harness.storageFiles).markAttached(List.of("evidence/proof.png"));
    }

    @Test
    void systemPardonAppendsThePardonAndItsNote() {
        harness.store(draft("PUNISH01", BAN, null, NOW, null));

        harness.lifecycle.systemPardonPunishment(server, PLAYER_UUID, "PUNISH01", "Auto-pardoned");

        verify(harness.punishments).appendModification(eq("PUNISH01"), eq(4),
            argThat(modification -> modification.type().equals("SYSTEM_PARDON") && modification.reason().equals("Auto-pardoned")),
            eq(PunishmentPatch.builder().status(PunishmentStatus.PARDONED).build()),
            argThat(notes -> notes.getFirst().text().equals("Auto-pardoned")));
    }

    @Test
    void theCascadePardonsOnlyLinkedBansInForce() {
        Punishment active = draft("LINKED01", Punishment.LINKED_BAN_TYPE_ORDINAL, null, NOW, null);
        Punishment ended = draft("LINKED02", Punishment.LINKED_BAN_TYPE_ORDINAL, 1L, NOW.minusSeconds(60), null);
        Punishment otherType = draft("OTHER001", BAN, null, NOW, null);
        when(harness.punishments.findLinkedBans("PARENTID"))
            .thenReturn(List.of(harness.store(active), harness.store(ended), harness.store(otherType)));

        assertThat(harness.lifecycle.cascadePardonLinkedBans(server, "PARENTID")).isEqualTo(1);

        verify(harness.punishments).appendModification(eq("LINKED01"), anyInt(),
            argThat(modification -> modification.type().equals("SYSTEM_PARDON")), any(), any());
        verify(harness.punishments, never()).appendModification(eq("LINKED02"), anyInt(), any(), any(), any());
        verify(harness.punishments, never()).appendModification(eq("OTHER001"), anyInt(), any(), any(), any());
    }

    @Test
    void pardoningARolledBackPunishmentIsANoOp() {
        Punishment rolledBack = draft("PUNISH01", BAN, null, NOW, null);
        rolledBack.getModifications().add(change("M1", "ROLLBACK", NOW, null));
        harness.store(rolledBack);

        PunishmentOperationResult result = harness.lifecycle.pardonPunishment(server, "PUNISH01", "Mod", null, null, null);

        assertThat(result.status()).isEqualTo(PunishmentOperationStatus.NO_OP);
        assertThat(result.message()).isEqualTo("Punishment has already been rolled back");
        verify(harness.punishments, never()).appendModification(anyString(), anyInt(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL_PARDON", "APPEAL_ACCEPT"})
    void aPanelPardonOfAnEndedPunishmentChangesNothing(String type) {
        Punishment rolledBack = draft("PUNISH01", BAN, null, NOW, null);
        rolledBack.data().setAltBlocking(true);
        rolledBack.getModifications().add(change("M1", "ROLLBACK", NOW, null));
        harness.store(rolledBack);

        harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest(type, "Mod", null, null, "Reviewed", "APPEAL-1"), Shortenings.PERMITTED);

        verify(harness.punishments, never()).appendModification(anyString(), anyInt(), any(), any(), any());
        verify(harness.punishments, never()).findLinkedBans(anyString());
        verifyNoInteractions(harness.realtime);
        if (type.equals("APPEAL_ACCEPT")) {
            verify(harness.appeals).applyOutcomeForPunishment(eq(server), eq("APPEAL-1"), eq("PUNISH01"), any(), eq("Mod"));
        }
    }

    @Test
    void aPanelPardonOfAPlainBanDoesNotLookForLinkedBans() {
        harness.store(draft("PUNISH01", BAN, null, NOW, null));

        harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest("MANUAL_PARDON", "Mod", null, null, "Reviewed", null), Shortenings.PERMITTED);

        verify(harness.punishments).appendModification(eq("PUNISH01"), eq(4), any(),
            eq(PunishmentPatch.builder().status(PunishmentStatus.PARDONED).build()), any());
        verify(harness.punishments, never()).findLinkedBans(anyString());
    }

    @Test
    void aClientCannotForgeARollbackAsAModification() {
        assertThatThrownBy(() -> harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest("ROLLBACK", "Mod", null, null, null, null), Shortenings.PERMITTED))
            .isInstanceOf(ValidationException.class);
        verifyNoInteractions(harness.punishments);
    }

    @ParameterizedTest
    @CsvSource({
        "SET_ALT_BLOCKING_TRUE, true, NULL",
        "SET_ALT_BLOCKING_FALSE, false, NULL",
        "SET_WIPING_TRUE, NULL, true",
        "SET_WIPING_FALSE, NULL, false"
    })
    void aPanelOptionChangeChangesTheFlagAndNeitherTheTimerNorTheQueue(String type, String altBlocking, String wiping) {
        harness.store(draft("PUNISH01", BAN, HOUR, null, null));
        PunishmentPatch expected = PunishmentPatch.builder()
            .altBlocking("NULL".equals(altBlocking) ? null : Boolean.valueOf(altBlocking))
            .wipeAfterExpiry("NULL".equals(wiping) ? null : Boolean.valueOf(wiping))
            .build();

        harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest(type, "Mod", null, HOUR, "Reviewed", null), Shortenings.PERMITTED);

        verify(harness.punishments).appendModification(eq("PUNISH01"), eq(4),
            argThat(modification -> modification.type().equals(type) && modification.effectiveDuration() == null),
            eq(expected), any());
        verify(harness.punishments, never()).markStarted(anyString(), any());
        verify(harness.realtime).punishmentModified(eq(server), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SET_ALT_BLOCKING_TRUE", "SET_WIPING_TRUE"})
    void thePanelCannotSwitchOnAnOptionTheTypeDoesNotOffer(String type) {
        harness.store(draft("PUNISH01", CONFIGURED, HOUR, NOW, null));

        assertThatThrownBy(() -> harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest(type, "Mod", null, null, "Reviewed", null), Shortenings.PERMITTED))
            .isInstanceOf(ValidationException.class);
        verify(harness.punishments, never()).appendModification(anyString(), anyInt(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SET_ALT_BLOCKING_FALSE", "SET_WIPING_FALSE"})
    void anOptionCanAlwaysBeSwitchedOff(String type) {
        harness.store(draft("PUNISH01", CONFIGURED, HOUR, NOW, null));

        harness.mutations.addModification(server, PLAYER_UUID, "PUNISH01",
            new AddModificationRequest(type, "Mod", null, null, "Reviewed", null), Shortenings.PERMITTED);

        verify(harness.punishments).appendModification(eq("PUNISH01"), eq(4), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SET_ALT_BLOCKING_TRUE", "SET_WIPING_TRUE"})
    void anOptionOfAnotherPlayersPunishmentCannotBeChanged(String type) {
        harness.store(draft("PUNISH01", BAN, null, NOW, null));

        assertThatThrownBy(() -> harness.mutations.addModification(server, UUID.randomUUID(), "PUNISH01",
            new AddModificationRequest(type, "Mod", null, null, "Reviewed", null), Shortenings.PERMITTED))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(harness.punishments, never()).appendModification(anyString(), anyInt(), any(), any(), any());
    }

    @Test
    void aPunishmentOfAnotherPlayerCannotBeModifiedThroughThisPlayersPath() {
        harness.store(draft("PUNISH01", BAN, null, NOW, null));
        UUID someoneElse = UUID.randomUUID();

        assertThatThrownBy(() -> harness.mutations.addModification(server, someoneElse, "PUNISH01",
            new AddModificationRequest("MANUAL_PARDON", "Mod", null, null, null, null), Shortenings.PERMITTED))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> harness.mutations.modifyPunishmentTickets(server, someoneElse, "PUNISH01",
            new ModifyPunishmentTicketsRequest(List.of("T-1"), null, true, "Mod", null)))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(harness.punishments, never()).appendModification(anyString(), anyInt(), any(), any(), any());
        verifyNoInteractions(harness.tickets);
    }

    @Test
    void ticketChangesCloseAddedAndReopenRemovedTickets() {
        Punishment punishment = draft("PUNISH01", BAN, null, NOW, null);
        punishment.getAttachedTicketIds().addAll(List.of("T-1", "T-2"));
        harness.store(punishment);

        harness.mutations.modifyPunishmentTickets(server, "PUNISH01",
            new ModifyPunishmentTicketsRequest(List.of("T-3"), List.of("T-1"), true, "Mod", null));

        verify(harness.punishments).patch("PUNISH01", 4, PunishmentPatch.NONE);
        verify(harness.punishments).replaceTickets("PUNISH01", List.of("T-2", "T-3"));
        verify(harness.tickets).closeTicketForPunishment(server, "T-3", "Mod");
        verify(harness.tickets).reopenTicketForPunishment(server, "T-1", "Mod");
        verify(harness.tickets, never()).closeTicketForPunishment(server, "T-2", "Mod");
    }

    private void assertInvalid(CreatePunishmentRequest request) {
        assertThatThrownBy(() -> harness.lifecycle.createPunishment(server, PLAYER_UUID, request))
            .isInstanceOf(ValidationException.class);
    }

    private static Punishment banned(String id) {
        Punishment banned = draft(id, BAN, null, NOW, null);
        banned.data().setEnforcementCategory(ResolvedCategory.BAN);
        return banned;
    }

    private static CreatePunishmentRequest request(int ordinal, String severity, Map<String, Object> data, Long duration) {
        return new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, ordinal, null, null, null, severity, null, data, "Rule broken", duration);
    }
}
