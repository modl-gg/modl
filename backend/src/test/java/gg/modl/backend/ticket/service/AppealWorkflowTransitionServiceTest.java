package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;

class AppealWorkflowTransitionServiceTest {
    private final Server server = TestServers.server("Demo", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void approvalClosesLocksAndNotifiesLinkedOpenAppeal(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(appeal("PUN-1", TicketStatus.OPEN, AppealWorkflowStatus.OPEN));

            harness.transitions().applyOutcomeForPunishment(server, "APPEAL-1", "PUN-1", AppealWorkflowStatus.APPROVED, "Admin");

            Ticket stored = harness.stored("APPEAL-1");
            assertEquals(AppealWorkflowStatus.APPROVED, stored.getAppealWorkflowStatus());
            assertEquals(TicketStatus.CLOSED, stored.getStatus());
            assertTrue(stored.isLocked());
            assertEquals(1, stored.getReplies().size());
            assertEquals("Appeal status changed to Approved.", stored.getReplies().getFirst().getContent());
            verify(harness.notifications(), times(1)).notifyTicketClosed(eq(server), any());
        }
    }

    @SqlContractTest
    void ignoresAppealNotLinkedToPunishment(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(appeal("OTHER", TicketStatus.OPEN, AppealWorkflowStatus.OPEN));

            harness.transitions().applyOutcomeForPunishment(server, "APPEAL-1", "PUN-1", AppealWorkflowStatus.APPROVED, "Admin");

            assertEquals(AppealWorkflowStatus.OPEN, harness.stored("APPEAL-1").getAppealWorkflowStatus());
            verifyNoInteractions(harness.notifications());
        }
    }

    @SqlContractTest
    void rejectsChangingOneTerminalOutcomeToAnother(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(appeal("PUN-1", TicketStatus.CLOSED, AppealWorkflowStatus.REJECTED));

            assertThrows(ConflictException.class, () -> harness.transitions().applyOutcomeForPunishment(
                server, "APPEAL-1", "PUN-1", AppealWorkflowStatus.APPROVED, "Admin"));

            assertEquals(AppealWorkflowStatus.REJECTED, harness.stored("APPEAL-1").getAppealWorkflowStatus());
        }
    }

    @SqlContractTest
    void noOpWhenOutcomeAlreadyApplied(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(appeal("PUN-1", TicketStatus.CLOSED, AppealWorkflowStatus.APPROVED));

            harness.transitions().applyOutcomeForPunishment(server, "APPEAL-1", "PUN-1", AppealWorkflowStatus.APPROVED, "Admin");

            assertEquals(0, harness.stored("APPEAL-1").getVersion());
            verifyNoInteractions(harness.notifications());
        }
    }

    private static Ticket.TicketBuilder appeal(String punishmentId, TicketStatus status, AppealWorkflowStatus workflow) {
        return ticket("APPEAL-1").type(TicketCategory.APPEAL).status(status).locked(status.isTerminal())
            .appealWorkflowStatus(workflow).data(Map.of("punishmentId", punishmentId));
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }
}
