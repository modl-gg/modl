package gg.modl.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.player.service.PunishmentShortening;
import gg.modl.backend.player.service.Shortenings;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AuditServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final int DELETED_TYPE = 18;
    private static final String PLAYER_UUID = UUID.randomUUID().toString();
    private static final PunishmentIssuer OWNER = PunishmentIssuer.staff("owner", null);
    private static final PunishmentOperationResult SUCCESS =
        new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "", true, 1);

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final StaffService staff = mock(StaffService.class);
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final PunishmentMutationService mutations = mock(PunishmentMutationService.class);
    private final ImmediateUnitOfWork unitOfWork = new ImmediateUnitOfWork();
    private final AuditService service = new AuditService(unitOfWork, punishments, auditLogs, types, staff,
        lifecycle, mutations, new IssuerNameResolver(mock(StaffRepository.class)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void bulkPardonOfADeletedTypeSkipsInactiveRowsAndAuditsTheMinecraftUuid() {
        PunishmentRecord active = found("ACTIVE01", NOW, null);
        PunishmentRecord expired = found("EXPIRED1", NOW.minus(Duration.ofDays(3)), Duration.ofDays(1).toMillis());
        pageOfTypes(List.of(DELETED_TYPE), active, expired);
        when(types.getPunishmentTypeName(server, DELETED_TYPE)).thenReturn("Cheating (deleted)");
        when(lifecycle.pardonPunishment(eq(server), eq("ACTIVE01"), eq("owner"), isNull(), eq("cleanup"), isNull())).thenReturn(SUCCESS);

        assertThat(service.bulkPardonByType(server, List.of(DELETED_TYPE, DELETED_TYPE), "cleanup", OWNER)).isEqualTo(1);

        verify(lifecycle, never()).pardonPunishment(any(), eq("EXPIRED1"), any(), any(), any(), any());
        AuditLog logged = loggedEntry();
        assertThat(logged.getMetadata()).containsEntry("playerId", PLAYER_UUID).containsEntry("punishmentId", "ACTIVE01");
        assertThat(logged.getDescription()).contains("Cheating (deleted)");
    }

    @Test
    void bulkExpirationTurnsZeroIntoPermanent() {
        pageOfTypes(List.of(DELETED_TYPE), found("ACTIVE01", NOW, null));
        when(types.getPunishmentTypeName(server, DELETED_TYPE)).thenReturn("Cheating (deleted)");
        when(mutations.changeDuration(server, "ACTIVE01", -1L, "owner", null, Shortenings.PERMITTED)).thenReturn(SUCCESS);

        assertThat(service.bulkSetExpirationByType(server, List.of(DELETED_TYPE), 0, "policy", OWNER, Shortenings.PERMITTED))
            .isEqualTo(1);

        assertThat(loggedEntry().getMetadata()).containsEntry("newDurationMs", 0L).containsEntry("playerId", PLAYER_UUID);
    }

    @Test
    void bulkExpirationPassesTheCallersShorteningRuleToEveryChange() {
        pageOfTypes(List.of(DELETED_TYPE), found("ACTIVE01", NOW, null));
        when(mutations.changeDuration(server, "ACTIVE01", 60_000L, "owner", null, Shortenings.REFUSED))
            .thenThrow(new ForbiddenException("refused", PunishmentShortening.NEEDS_PARDON));

        assertThatThrownBy(() -> service.bulkSetExpirationByType(server, List.of(DELETED_TYPE), 60_000L, "policy", OWNER,
            Shortenings.REFUSED)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aBulkPageIsWrittenInShortUnitsSoTheWriterIsNeverHeldForAWholePage() {
        PunishmentRecord[] page = IntStream.range(0, 2 * AuditService.BULK_WRITE_UNIT_SIZE + 1)
            .mapToObj(index -> found("ACTIVE%03d".formatted(index), NOW, null))
            .toArray(PunishmentRecord[]::new);
        pageOfTypes(List.of(DELETED_TYPE), page);
        when(types.getPunishmentTypeName(server, DELETED_TYPE)).thenReturn("Cheating (deleted)");
        when(lifecycle.pardonPunishment(eq(server), any(), eq("owner"), isNull(), eq("cleanup"), isNull())).thenReturn(SUCCESS);

        assertThat(service.bulkPardonByType(server, List.of(DELETED_TYPE), "cleanup", OWNER)).isEqualTo(page.length);

        assertThat(unitOfWork.writes()).isEqualTo(3);
    }

    @Test
    void anEmptyTypeListChangesNothing() {
        assertThat(service.bulkPardonByType(server, List.of(), "cleanup", OWNER)).isZero();
        verifyNoInteractions(punishments, lifecycle, auditLogs);
    }

    @Test
    void tooManyTypesAreRejectedBeforeAnyRowIsRead() {
        List<Integer> ordinals = IntStream.range(0, AuditService.BULK_TYPES_MAX + 1).boxed().toList();

        assertThatThrownBy(() -> service.bulkPardonByType(server, ordinals, "cleanup", OWNER))
            .isInstanceOf(ValidationException.class);
        verifyNoInteractions(punishments, lifecycle);
    }

    @Test
    void aPageEndsParentsBeforeTheirLinkedBans() {
        PunishmentRecord linked = found("AAAALINK", NOW, null);
        linked.punishment().setTypeOrdinal(Punishment.LINKED_BAN_TYPE_ORDINAL);
        PunishmentRecord parent = found("ZZZZPARE", NOW, null);
        pageOfTypes(List.of(DELETED_TYPE, Punishment.LINKED_BAN_TYPE_ORDINAL), linked, parent);
        when(lifecycle.pardonPunishment(eq(server), any(), any(), any(), any(), any())).thenReturn(SUCCESS);

        service.bulkPardonByType(server, List.of(DELETED_TYPE, Punishment.LINKED_BAN_TYPE_ORDINAL), "cleanup", OWNER);

        InOrder order = inOrder(lifecycle);
        order.verify(lifecycle).pardonPunishment(eq(server), eq("ZZZZPARE"), any(), any(), any(), any());
        order.verify(lifecycle).pardonPunishment(eq(server), eq("AAAALINK"), any(), any(), any(), any());
    }

    @Test
    void dateRangeRollbackAsksTheRepositoryForTheRangeAndRollsBackWhatItReturns() {
        Instant start = NOW.minus(Duration.ofDays(2));
        PunishmentRecord atStart = found("START001", start, null);
        PunishmentRecord atEnd = found("END00001", NOW, null);
        when(staff.getStaffByUsername(server, "Alice")).thenReturn(Optional.empty());
        when(types.getPunishmentTypeName(server, DELETED_TYPE)).thenReturn("Cheating (deleted)");
        when(punishments.pageIdsByIssuer(new IssuerMatch("Alice", null), start, NOW, null, AuditService.BULK_PAGE_SIZE))
            .thenReturn(List.of("START001", "END00001"));
        stubFound(atStart, atEnd);
        when(lifecycle.rollbackPunishment(eq(server), any(), eq(OWNER), eq("mistake"))).thenReturn(SUCCESS);

        assertThat(service.rollbackPunishmentsByDateRange(server, "Alice", start, NOW, "mistake", OWNER)).isEqualTo(2);

        verify(lifecycle).rollbackPunishment(server, "START001", OWNER, "mistake");
        verify(lifecycle).rollbackPunishment(server, "END00001", OWNER, "mistake");
        verify(punishments, never()).appendModification(any(), anyInt(), any(), any(), any());
    }

    @Test
    void aSecondRollbackIsRejected() {
        PunishmentRecord target = found("ROLLED01", NOW, null);
        target.punishment().getModifications().add(new PunishmentModification(
            "m1", PunishmentModificationType.ROLLBACK.name(), NOW, "owner", null, "mistake", null, null, null));
        when(punishments.findById("ROLLED01")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.rollbackPunishment(server, "ROLLED01", "again", OWNER))
            .isInstanceOf(ValidationException.class);
        verifyNoInteractions(auditLogs);
    }

    @Test
    void rollingBackAnUnknownPunishmentReportsNotFound() {
        when(punishments.findById("MISSING1")).thenReturn(Optional.empty());

        assertThat(service.rollbackPunishment(server, "MISSING1", "mistake", OWNER)).isFalse();
        verifyNoInteractions(auditLogs);
    }

    private void pageOfTypes(List<Integer> ordinals, PunishmentRecord... page) {
        when(punishments.pageIdsByTypes(ordinals, PunishmentPhase.NOT_ENDED, NOW, null, AuditService.BULK_PAGE_SIZE))
            .thenReturn(Arrays.stream(page).map(record -> record.punishment().getId()).toList());
        stubFound(page);
    }

    private void stubFound(PunishmentRecord... records) {
        for (PunishmentRecord record : records) {
            when(punishments.findById(record.punishment().getId())).thenReturn(Optional.of(record));
        }
    }

    private AuditLog loggedEntry() {
        ArgumentCaptor<AuditLog> logged = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).insert(logged.capture());
        return logged.getValue();
    }

    private static PunishmentRecord found(String id, Instant startedAt, Long duration) {
        Punishment punishment = new Punishment();
        punishment.setId(id);
        punishment.setTypeOrdinal(DELETED_TYPE);
        punishment.setIssued(startedAt);
        punishment.setStarted(startedAt);
        Map<String, Object> data = new HashMap<>();
        data.put("duration", duration);
        punishment.replaceData(data);
        return new PunishmentRecord("internalplayerid", PLAYER_UUID, "Target", punishment, 3);
    }
}
