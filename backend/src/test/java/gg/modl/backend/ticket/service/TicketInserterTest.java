package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.Ticket;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;

class TicketInserterTest {
    private static final String BASE64URL_WITHOUT_PADDING = "[A-Za-z0-9_-]{43}";
    private static final int FIRST_ID = 100000;

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void insertNewAssignsPrefixedSixDigitPublicIdAndAccessKey(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            Ticket inserted = harness.inserter().insertNew(server, "SUPPORT", ticket("ignored").build());

            assertTrue(inserted.getId().matches("SUPPORT-\\d{6}"));
            assertTrue(harness.stored(inserted.getId()).getPublicAccessKey().matches(BASE64URL_WITHOUT_PADDING));
        }
    }

    @SqlContractTest
    void insertNewSkipsTakenIds(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("SUPPORT-100000").subject("existing"), ticket("SUPPORT-100001").subject("existing"));
            AtomicInteger next = new AtomicInteger(FIRST_ID);

            Ticket inserted = inserter(harness, new IdGenerator() {
                @Override
                public int nextSixDigitInt() {
                    return next.getAndIncrement();
                }
            }).insertNew(server, "SUPPORT", ticket("ignored").build());

            assertEquals("SUPPORT-100002", inserted.getId());
            assertEquals("existing", harness.stored("SUPPORT-100000").getSubject());
        }
    }

    @SqlContractTest
    void insertNewGivesUpAfterRepeatedCollisions(DialectTarget target) {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory, new RetryInjection())) {
            harness.insert(ticket("SUPPORT-100000"));
            TicketInserter colliding = inserter(harness, new IdGenerator() {
                @Override
                public int nextSixDigitInt() {
                    return FIRST_ID;
                }
            });

            assertThrows(ConcurrentUpdateException.class,
                () -> colliding.insertNew(server, "SUPPORT", ticket("ignored").build()));
        }
    }

    private static TicketInserter inserter(TicketHarness harness, IdGenerator ids) {
        return new TicketInserter(ids, new TicketAccessKeys(ids), harness.tickets(), harness.database().unitOfWork());
    }
}
