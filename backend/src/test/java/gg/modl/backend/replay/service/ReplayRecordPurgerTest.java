package gg.modl.backend.replay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplayRecordPurgerTest {
    private static final String R1_KEY = "db/replays/r1.modlreplay";
    private static final String EVIDENCE_KEY = "db/evidence/e1.png";

    private ReplayRepository replayRepository;
    private TicketRepository ticketRepository;
    private ReplayRecordPurger purger;
    private ImmediateUnitOfWork unitOfWork;
    private Server server;

    @BeforeEach
    void setUp() {
        replayRepository = mock(ReplayRepository.class);
        ticketRepository = mock(TicketRepository.class);
        unitOfWork = new ImmediateUnitOfWork();
        purger = new ReplayRecordPurger(replayRepository, ticketRepository, unitOfWork);
        server = TestServers.server("server", "admin@example.com");
    }

    @Test
    void deletedReplayFilesPurgeTheirRecordsAndTicketReferences() {
        when(replayRepository.findByStorageKeys(List.of(R1_KEY))).thenReturn(List.of(replay("r1", R1_KEY)));
        when(replayRepository.deleteByIds(List.of("r1"))).thenReturn(1L);

        purger.onStoredObjectsDeleted(server, List.of(R1_KEY, EVIDENCE_KEY));

        verify(replayRepository).findByStorageKeys(List.of(R1_KEY));
        verify(replayRepository).deleteByIds(List.of("r1"));
        verify(ticketRepository).clearReplayReferences(List.of("r1"));
    }

    @Test
    void deletedNonReplayFilesAreIgnored() {
        purger.onStoredObjectsDeleted(server, List.of(EVIDENCE_KEY));

        verify(replayRepository, never()).findByStorageKeys(any());
        verify(replayRepository, never()).deleteByIds(any());
    }

    @Test
    void deletedReplayFilesWithoutRecordsChangeNothing() {
        when(replayRepository.findByStorageKeys(anyList())).thenReturn(List.of());

        purger.onStoredObjectsDeleted(server, List.of(R1_KEY));

        verify(replayRepository, never()).deleteByIds(any());
        verify(ticketRepository, never()).clearReplayReferences(any());
    }

    @Test
    void purgeReturnsTheNumberOfRemovedRecords() {
        when(replayRepository.deleteByIds(List.of("r1"))).thenReturn(1L);

        assertEquals(1L, purger.purge(List.of("r1")));
        assertEquals(0L, purger.purge(List.of()));
    }

    @Test
    void recordsAndTicketReferencesChangeInOneUnitPerFiveHundredReplays() {
        List<String> ids = IntStream.range(0, 501).mapToObj(index -> "r" + index).toList();

        purger.purge(ids);

        assertEquals(2, unitOfWork.writes());
    }

    private ReplayDocument replay(String id, String storageKey) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setStorageKey(storageKey);
        replay.setStatus(ReplayDocument.STATUS_PENDING);
        return replay;
    }
}
