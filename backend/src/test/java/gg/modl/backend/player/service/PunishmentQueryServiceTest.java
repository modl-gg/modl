package gg.modl.backend.player.service;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.AppealEligibility;
import gg.modl.backend.player.dto.response.AppealInfoView;
import gg.modl.backend.player.dto.response.PunishmentSearchResult;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.repository.PunishmentSearchRow;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PunishmentQueryServiceTest {

    @Test
    void publicAppealEligibilityReturnsPlayerUuidAndLatestExistingAppealDetails() {
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        PunishmentRepository punishmentRepository = mock(PunishmentRepository.class);
        PlayerStatusCalculator statusCalculator = mock(PlayerStatusCalculator.class);
        PunishmentTypeService punishmentTypeService = mock(PunishmentTypeService.class);
        TicketRepository ticketRepository = mock(TicketRepository.class);
        PunishmentQueryService service = new PunishmentQueryService(
            Clock.systemUTC(),
            playerRepository,
            punishmentRepository,
            statusCalculator,
            punishmentTypeService,
            mock(OffenderThresholdSettingsService.class),
            mock(EvidenceUploadTokenService.class),
            mock(IssuerNameResolver.class),
            ticketRepository
        );
        Server server = TestServers.server("server", "admin@example.com");
        UUID playerUuid = UUID.randomUUID();
        Instant issued = Instant.ofEpochMilli(1_700_000_000_000L);
        Instant started = Instant.ofEpochMilli(1_700_000_001_000L);
        Punishment punishment = new Punishment();
        punishment.setId("punishment-1");
        punishment.setTypeOrdinal(2);
        punishment.setIssued(issued);
        punishment.setStarted(started);
        punishment.setModifications(new ArrayList<>());
        punishment.setNotes(new ArrayList<>());
        punishment.setEvidence(new ArrayList<>());
        punishment.setAttachedTicketIds(new ArrayList<>());
        punishment.replaceData(Map.of());
        Ticket olderAppeal = Ticket.builder()
            .id("APPEAL-111111")
            .created(Instant.ofEpochMilli(1_700_000_002_000L))
            .status(TicketStatus.OPEN)
            .appealWorkflowStatus(AppealWorkflowStatus.OPEN)
            .build();
        Ticket latestAppeal = Ticket.builder()
            .id("APPEAL-222222")
            .created(Instant.ofEpochMilli(1_700_000_003_000L))
            .status(TicketStatus.OPEN)
            .appealWorkflowStatus(AppealWorkflowStatus.REJECTED)
            .build();
        PunishmentType punishmentType = new PunishmentType();

        when(punishmentRepository.findById("punishment-1")).thenReturn(Optional.of(
            new PunishmentRecord("player0000000001", playerUuid.toString(), "Steve", punishment, 0)));
        when(statusCalculator.getEffectiveCategory(punishment)).thenReturn(ResolvedCategory.BAN);
        when(statusCalculator.isPunishmentActive(punishment)).thenReturn(true);
        when(statusCalculator.getEffectiveExpiry(punishment)).thenReturn(null);
        when(punishmentTypeService.getPunishmentTypeName(server, 2)).thenReturn("Ban");
        when(punishmentTypeService.isAppealable(server, 2)).thenReturn(true);
        when(punishmentTypeService.getPunishmentTypeByOrdinal(server, 2)).thenReturn(Optional.of(punishmentType));
        when(ticketRepository.findAppealsByPunishmentId("punishment-1")).thenReturn(List.of(olderAppeal, latestAppeal));

        AppealEligibility result = service.getPublicPunishmentWithAppealEligibility(server, "punishment-1").orElseThrow();

        assertTrue(result instanceof AppealEligibility.Eligible);
        AppealInfoView info = ((AppealEligibility.Eligible) result).info();
        assertEquals(playerUuid.toString(), info.playerUuid());
        assertTrue(info.existingAppeal() != null);
        Map<String, Object> existingAppeal = info.existingAppeal();
        assertEquals("APPEAL-222222", existingAppeal.get("id"));
        assertEquals(latestAppeal.getCreated(), existingAppeal.get("submittedDate"));
        assertEquals("open", existingAppeal.get("status"));
        assertEquals("rejected", existingAppeal.get("appealWorkflowStatus"));
        assertEquals(false, existingAppeal.get("locked"));
    }

    @Test
    void previewPunishmentLowercasesUuidBeforeQueryingPlayerRepository() {
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        PunishmentQueryService service = new PunishmentQueryService(
            Clock.systemUTC(),
            playerRepository,
            mock(PunishmentRepository.class),
            mock(PlayerStatusCalculator.class),
            mock(PunishmentTypeService.class),
            mock(OffenderThresholdSettingsService.class),
            mock(EvidenceUploadTokenService.class),
            mock(IssuerNameResolver.class),
            mock(TicketRepository.class)
        );
        Server server = TestServers.server("server", "admin@example.com");
        when(playerRepository.findByMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
            .thenReturn(Optional.empty());

        service.previewPunishment(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", 1);

        verify(playerRepository).findByMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    void searchFindsAnOldBanByItsExactIdAndByItsPlayerName() {
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        PunishmentRepository punishmentRepository = mock(PunishmentRepository.class);
        PlayerStatusCalculator statusCalculator = mock(PlayerStatusCalculator.class);
        PunishmentQueryService service = new PunishmentQueryService(
            Clock.systemUTC(),
            playerRepository,
            punishmentRepository,
            statusCalculator,
            mock(PunishmentTypeService.class),
            mock(OffenderThresholdSettingsService.class),
            mock(EvidenceUploadTokenService.class),
            mock(IssuerNameResolver.class),
            mock(TicketRepository.class)
        );
        Server server = TestServers.server("server", "admin@example.com");
        Player steve = Player.builder()
            .id("player0000000001")
            .minecraftUuid(UUID.randomUUID())
            .usernames(new ArrayList<>(List.of(new UsernameEntry("Steve", null))))
            .build();
        PunishmentSearchRow oldBan = new PunishmentSearchRow("OLDBANAA", "Steve", 2, "Console", null, "Cheating",
            Instant.EPOCH, Instant.MAX);
        when(punishmentRepository.findRecentSearchRows(anyInt())).thenReturn(List.of());
        when(punishmentRepository.findSearchRow(any())).thenReturn(Optional.empty());
        when(punishmentRepository.findSearchRow("OLDBANAA")).thenReturn(Optional.of(oldBan));
        when(playerRepository.findByUsernameIgnoreCase("steve")).thenReturn(Optional.of(steve));
        when(punishmentRepository.findSearchRowsOfPlayer(steve.getId())).thenReturn(List.of(oldBan));

        for (String query : List.of("OLDBANAA", "steve")) {
            List<PunishmentSearchResult> results = service.searchPunishments(server, query, true);

            assertEquals(1, results.size());
            assertEquals("OLDBANAA", results.getFirst().id());
            assertEquals("Steve", results.getFirst().playerName());
            assertEquals("Active", results.getFirst().status());
        }
    }
}
