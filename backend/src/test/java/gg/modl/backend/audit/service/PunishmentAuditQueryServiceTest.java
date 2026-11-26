package gg.modl.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.audit.dto.response.AuditPunishmentTypeResponse;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentListCursor;
import gg.modl.backend.player.repository.PunishmentListPage;
import gg.modl.backend.player.repository.PunishmentListQuery;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.support.TestServers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PunishmentAuditQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final PunishmentListRequest DEFAULT_REQUEST = new PunishmentListRequest("active", "issued", "desc", null, null, "all");
    private static final PunishmentListQuery DEFAULT_QUERY = new PunishmentListQuery(PunishmentPhase.ACTIVE,
        PunishmentListQuery.SortKey.ISSUED, false, null, null, PunishmentListQuery.Evidence.ANY);

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final IssuerNameResolver issuers = mock(IssuerNameResolver.class);
    private final StaffService staff = mock(StaffService.class);
    private final PunishmentAuditQueryService service = new PunishmentAuditQueryService(punishments, types, issuers,
        staff, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void deletedTypesAndOpaqueIssuerIdsAreResolved() {
        Punishment punishment = new Punishment();
        punishment.setId("BAN00001");
        punishment.setTypeOrdinal(18);
        punishment.setIssuerId("k7m2qz9xv4b8c1d3");
        punishment.setIssued(NOW);
        when(punishments.pageListed(DEFAULT_QUERY, NOW, null, 200))
            .thenReturn(new PunishmentListPage(List.of(new PunishmentRecord("internal", "uuid", "Target", punishment, 0)), null));
        when(types.getPunishmentTypeName(server, 18)).thenReturn("Cheating (deleted)");
        when(issuers.resolveForPunishments(eq(server), argThat(listed -> listed.stream()
            .anyMatch(candidate -> "k7m2qz9xv4b8c1d3".equals(candidate.getIssuerId())))))
            .thenReturn(Map.of("k7m2qz9xv4b8c1d3", "Alice"));

        assertThat(service.getPunishmentsList(server, DEFAULT_REQUEST, 200, null).punishments()).singleElement().satisfies(listed -> {
            assertThat(listed.type()).isEqualTo("Cheating (deleted)");
            assertThat(listed.staffName()).isEqualTo("Alice");
            assertThat(listed.playerId()).isEqualTo("uuid");
            assertThat(listed.effectiveCategory()).isNull();
        });
    }

    @Test
    void filtersAndSortReachTheRepositoryAndTheNextCursorRoundTrips() {
        when(staff.getStaffByUsername(server, "Alice")).thenReturn(Optional.of(staffNamed("staff-a")));
        PunishmentListQuery expected = new PunishmentListQuery(null, PunishmentListQuery.SortKey.DURATION,
            true, new IssuerMatch("Alice", "staff-a"), 7, PunishmentListQuery.Evidence.WITHOUT);
        PunishmentListCursor next = new PunishmentListCursor(Long.MAX_VALUE, "P1");
        when(punishments.pageListed(expected, NOW, null, 50)).thenReturn(new PunishmentListPage(List.of(), next));
        when(punishments.pageListed(expected, NOW, next, 50)).thenReturn(new PunishmentListPage(List.of(), null));
        PunishmentListRequest request = new PunishmentListRequest("all", "duration", "asc", " Alice ", 7, "no");

        String cursor = service.getPunishmentsList(server, request, 50, null).nextCursor();

        assertThat(cursor).isNotNull();
        assertThat(service.getPunishmentsList(server, request, 50, cursor).hasMore()).isFalse();
        assertThatThrownBy(() -> service.getPunishmentsList(server, DEFAULT_REQUEST, 50, cursor)).isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "sort", "direction", "evidence"})
    void anUnsupportedListParameterIsABadRequest(String parameter) {
        PunishmentListRequest request = new PunishmentListRequest(
            parameter.equals("status") ? "everything" : "active",
            parameter.equals("sort") ? "reason" : "issued",
            parameter.equals("direction") ? "up" : "desc",
            null,
            null,
            parameter.equals("evidence") ? "maybe" : "all");

        assertThatThrownBy(() -> service.getPunishmentsList(server, request, 50, null)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(punishments);
    }

    @Test
    void punishmentTypesListConfiguredTypesAndCountedOrdinalsWithTheirActiveAndUnstartedCounts() {
        PunishmentType configured = new PunishmentType();
        configured.setOrdinal(2);
        when(types.getPunishmentTypes(server)).thenReturn(List.of(configured));
        when(types.getPunishmentTypeName(server, 2)).thenReturn("Chat abuse");
        when(types.getPunishmentTypeName(server, 18)).thenReturn("Cheating (deleted)");
        when(types.getPunishmentTypeName(server, 21)).thenReturn("Spam (deleted)");
        when(punishments.countByType(PunishmentPhase.ACTIVE, NOW)).thenReturn(Map.of(18, 3L));
        when(punishments.countByType(PunishmentPhase.UNSTARTED, NOW)).thenReturn(Map.of(18, 1L, 21, 2L));

        assertThat(service.getPunishmentTypes(server)).containsExactly(
            new AuditPunishmentTypeResponse(2, "Chat abuse", 0, 0),
            new AuditPunishmentTypeResponse(18, "Cheating (deleted)", 3, 1),
            new AuditPunishmentTypeResponse(21, "Spam (deleted)", 0, 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISSUED:d:nocolon", "ISSUED:d:abc:id", "ISSUED:d:9223372036854775808:id", "ISSUED:d::id", "ISSUED:d:5:",
        "5:id", "DURATION:d:5:id", "ISSUED:a:5:id"})
    void aMalformedCursorIsABadRequest(String value) {
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.getPunishmentsList(server, DEFAULT_REQUEST, 200, cursor)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(punishments);
    }

    @Test
    void aCursorThatIsNotBase64IsABadRequest() {
        assertThatThrownBy(() -> service.getPunishmentsList(server, DEFAULT_REQUEST, 200, "%%%")).isInstanceOf(ValidationException.class);
    }

    private static StaffResponse staffNamed(String id) {
        return new StaffResponse(id, "alice@example.com", "Alice", "Moderator", "active", null, null, NOW);
    }
}
