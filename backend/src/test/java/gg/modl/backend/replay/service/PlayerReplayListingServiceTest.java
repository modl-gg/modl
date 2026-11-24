package gg.modl.backend.replay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.dto.PlayerReplayResponse;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.service.TicketPublicLinks;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlayerReplayListingServiceTest {

    @Mock
    private ReplayRepository replayRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private StorageMetadataService storageMetadataService;

    @Mock
    private TicketRepository ticketRepository;

    private PlayerReplayListingService listingService;
    private Server server;

    @BeforeEach
    void setUp() {
        listingService = new PlayerReplayListingService(
            replayRepository,
            objectStorage,
            storageMetadataService,
            ticketRepository,
            new TicketPublicLinks(mock(PublicUrls.class))
        );
        server = TestServers.server("server", "admin@example.com");
    }

    @Test
    void listPlayerReplaysCombinesDirectMetadataAndTicketFallbackWithoutDuplicateUrls() {
        String requestedPlayerUuid = "3F8C9C5A-6B6E-4F2C-9B7F-1A2B3C4D5E6F";
        String normalizedPlayerUuid = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";

        ReplayDocument directReplay = new ReplayDocument();
        directReplay.setId("replay-1");
        directReplay.setTargetUuid(normalizedPlayerUuid);
        directReplay.setTargetName("Player");
        directReplay.setMcVersion("1.21.4");
        directReplay.setFileSize(4096L);
        directReplay.setCreatedAt(Instant.ofEpochMilli(1000L));
        directReplay.setStatus(ReplayDocument.STATUS_COMPLETE);
        directReplay.setStorageKey("db/replays/replay-1.modlreplay");

        Ticket duplicateUrlTicket = Ticket.builder()
            .id("ticket-1")
            .creatorUuid(normalizedPlayerUuid)
            .creatorName("Player")
            .created(Instant.ofEpochMilli(2000L))
            .replayUrl("/v1/storage/objects/db/replays/replay-1.modlreplay")
            .build();
        Ticket duplicateRawIdTicket = Ticket.builder()
            .id("ticket-raw-duplicate")
            .creatorUuid(normalizedPlayerUuid)
            .creatorName("Player")
            .created(Instant.ofEpochMilli(2500L))
            .replayUrl("replay-1")
            .build();
        Ticket duplicateQueryIdTicket = Ticket.builder()
            .id("ticket-query-duplicate")
            .creatorUuid(normalizedPlayerUuid)
            .creatorName("Player")
            .created(Instant.ofEpochMilli(2750L))
            .replayUrl("https://replays.example/?id=replay-1")
            .build();
        Ticket fallbackTicket = Ticket.builder()
            .id("ticket-2")
            .reportedPlayerUuid(normalizedPlayerUuid)
            .reportedPlayer("Player")
            .created(Instant.ofEpochMilli(3000L))
            .replayUrl("replay-2")
            .build();

        when(replayRepository.findByTargetUuid(normalizedPlayerUuid, 100)).thenReturn(List.of(directReplay));
        when(storageMetadataService.existingKeys(eq(server), any())).thenReturn(Set.of("db/replays/replay-1.modlreplay"));
        when(objectStorage.publicUrl("db/replays/replay-1.modlreplay"))
            .thenReturn("/v1/storage/objects/db/replays/replay-1.modlreplay");
        when(ticketRepository.findPlayerTicketsWithReplayUrl(normalizedPlayerUuid, 100))
            .thenReturn(List.of(duplicateUrlTicket, duplicateRawIdTicket, duplicateQueryIdTicket, fallbackTicket));

        List<PlayerReplayResponse> replays = listingService.listPlayerReplays(server, requestedPlayerUuid);

        assertEquals(2, replays.size());
        assertEquals(PlayerReplayResponse.MatchSource.DIRECT_METADATA, replays.get(0).matchSource());
        assertEquals("replay-1", replays.get(0).replayId());
        assertEquals(PlayerReplayResponse.MatchSource.TICKET_FALLBACK, replays.get(1).matchSource());
        assertEquals("replay-2", replays.get(1).replayId());
        assertEquals("replay-2", replays.get(1).replayUrl());
    }

    @Test
    void listPlayerReplaysHidesCompleteReplayWhoseFileNoLongerExists() {
        String playerUuid = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";
        ReplayDocument orphaned = completeReplay("replay-gone", playerUuid, "db/replays/replay-gone.modlreplay");

        when(replayRepository.findByTargetUuid(playerUuid, 100)).thenReturn(List.of(orphaned));
        when(storageMetadataService.existingKeys(eq(server), any())).thenReturn(Set.of());
        when(ticketRepository.findPlayerTicketsWithReplayUrl(playerUuid, 100)).thenReturn(List.of());

        List<PlayerReplayResponse> replays = listingService.listPlayerReplays(server, playerUuid);

        assertEquals(0, replays.size());
    }

    @Test
    void listPlayerReplaysServesCompleteReplaysThroughTheirStoragePublicUrl() {
        String playerUuid = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";
        ReplayDocument replay = completeReplay("replay-1", playerUuid, "db/replays/replay-1.modlreplay");

        when(replayRepository.findByTargetUuid(playerUuid, 100)).thenReturn(List.of(replay));
        when(storageMetadataService.existingKeys(eq(server), any())).thenReturn(Set.of("db/replays/replay-1.modlreplay"));
        when(objectStorage.publicUrl("db/replays/replay-1.modlreplay")).thenReturn("/v1/storage/objects/db/replays/replay-1.modlreplay");
        when(ticketRepository.findPlayerTicketsWithReplayUrl(playerUuid, 100)).thenReturn(List.of());

        List<PlayerReplayResponse> replays = listingService.listPlayerReplays(server, playerUuid);

        assertEquals(1, replays.size());
        assertEquals("/v1/storage/objects/db/replays/replay-1.modlreplay", replays.get(0).replayUrl());
    }

    @Test
    void listPlayerReplaysSkipsTicketFallbackWhenReferencedReplayFileIsGone() {
        String playerUuid = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";
        ReplayDocument orphaned = completeReplay("replay-1", playerUuid, "db/replays/replay-1.modlreplay");
        Ticket ticket = Ticket.builder()
            .id("ticket-1")
            .creatorUuid(playerUuid)
            .creatorName("Player")
            .created(Instant.ofEpochMilli(2000L))
            .replayUrl("https://replays.example/?id=replay-1")
            .build();

        when(replayRepository.findByTargetUuid(playerUuid, 100)).thenReturn(List.of(orphaned));
        when(storageMetadataService.existingKeys(eq(server), any())).thenReturn(Set.of());
        when(ticketRepository.findPlayerTicketsWithReplayUrl(playerUuid, 100)).thenReturn(List.of(ticket));

        List<PlayerReplayResponse> replays = listingService.listPlayerReplays(server, playerUuid);

        assertEquals(0, replays.size());
    }

    private ReplayDocument completeReplay(String id, String targetUuid, String storageKey) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setTargetUuid(targetUuid);
        replay.setTargetName("Player");
        replay.setMcVersion("1.21.4");
        replay.setFileSize(4096L);
        replay.setCreatedAt(Instant.ofEpochMilli(1000L));
        replay.setStatus(ReplayDocument.STATUS_COMPLETE);
        replay.setStorageKey(storageKey);
        return replay;
    }
}
