package gg.modl.backend.replay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.database.DatabaseUnavailableException;
import gg.modl.backend.database.DatabaseUnavailableException.Reason;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.service.StorageDeletion;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ReplayDeletionServiceTest {
    private static final String R1_KEY = "db/replays/r1.modlreplay";
    private static final String R2_KEY = "db/replays/r2.modlreplay";

    private final Server server = TestServers.server("server", "admin@example.com");
    private ReplayRepository replayRepository;
    private TicketRepository ticketRepository;
    private StorageDeletionService storageDeletionService;
    private ImmediateUnitOfWork unitOfWork;
    private ReplayDeletionService service;

    @BeforeEach
    void setUp() {
        replayRepository = mock(ReplayRepository.class);
        ticketRepository = mock(TicketRepository.class);
        storageDeletionService = mock(StorageDeletionService.class);
        unitOfWork = new ImmediateUnitOfWork();
        service = new ReplayDeletionService(
            new ReplayRecordPurger(replayRepository, ticketRepository, unitOfWork), storageDeletionService);
    }

    @Test
    void storedObjectsAreRemovedOutsideTheTransactionBeforeTheirRecords() {
        doAnswer(invocation -> {
            assertFalse(unitOfWork.insideWrite());
            return StorageDeletion.NOTHING;
        }).when(storageDeletionService).delete(any(), any());

        long deleted = service.deleteReplaysWithStorage(server, List.of(replay("r1", R1_KEY), replay("r2", R2_KEY)));

        assertEquals(2L, deleted);
        InOrder order = inOrder(replayRepository, ticketRepository, storageDeletionService);
        order.verify(storageDeletionService).delete(server, List.of(R1_KEY, R2_KEY));
        order.verify(replayRepository).deleteByIds(List.of("r1", "r2"));
        order.verify(ticketRepository).clearReplayReferences(List.of("r1", "r2"));
    }

    @Test
    void recordsStayForTheNextRunWhenStorageDeletionFails() {
        doThrow(new DatabaseUnavailableException(Reason.SHUTTING_DOWN)).when(storageDeletionService).delete(any(), any());

        assertThrows(DatabaseUnavailableException.class,
            () -> service.deleteReplaysWithStorage(server, List.of(replay("r1", R1_KEY))));

        verifyNoInteractions(replayRepository, ticketRepository);
    }

    @Test
    void blankAndMissingStorageKeysOnlyPurgeTheRecord() {
        assertEquals(2L, service.deleteReplaysWithStorage(server, List.of(replay("r1", "  "), replay("r2", null))));

        verify(storageDeletionService).delete(server, List.of());
        verify(replayRepository).deleteByIds(List.of("r1", "r2"));
    }

    @Test
    void anEmptyBatchDoesNothing() {
        assertEquals(0L, service.deleteReplaysWithStorage(server, List.of()));

        verifyNoInteractions(storageDeletionService, replayRepository, ticketRepository);
    }

    private static ReplayDocument replay(String id, String storageKey) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setStorageKey(storageKey);
        replay.setStatus(ReplayDocument.STATUS_COMPLETE);
        return replay;
    }
}
