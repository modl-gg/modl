package gg.modl.backend.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TicketNoteVisibilityTest {
    private static final String EMAIL = "helper@example.com";

    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final RoleAuthorization roles = mock(RoleAuthorization.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final TicketNoteVisibility visibility = new TicketNoteVisibility(roles, permissions);

    @Test
    void aViewerWithoutTheNotePermissionGetsNoNotesInThePayload() {
        staffHolding(PermissionService.TICKET_VIEW_ALL);

        TicketResponse shown = visibility.visibleTo(server, EMAIL, ticketWithNote());

        assertThat(shown.notes()).isEmpty();
        assertThat(shown.messages()).isEqualTo(ticketWithNote().messages());
        assertThat(visibility.visibleTo(server, EMAIL, List.of(ticketWithNote()))).allSatisfy(ticket -> assertThat(ticket.notes()).isEmpty());
    }

    @Test
    void aViewerWithTheNotePermissionSeesTheNotes() {
        staffHolding(PermissionService.TICKET_VIEW_ALL, PermissionService.TICKET_VIEW_ALL_NOTES);

        assertThat(visibility.visibleTo(server, EMAIL, ticketWithNote()).notes()).hasSize(1);
    }

    @Test
    void theOwnerSeesNotesAndAnUnknownCallerDoesNot() {
        when(roles.panelPerformer(server, "owner@example.com"))
            .thenReturn(new PerformerAuthority("owner@example.com", null, true, true));
        when(roles.panelPerformer(server, null)).thenReturn(PerformerAuthority.unidentified());

        assertThat(visibility.visibleTo(server, "owner@example.com", ticketWithNote()).notes()).hasSize(1);
        assertThat(visibility.visibleTo(server, null, ticketWithNote()).notes()).isEmpty();
    }

    private void staffHolding(String... held) {
        List<String> granted = List.of(held);
        when(roles.panelPerformer(server, EMAIL)).thenReturn(new PerformerAuthority(EMAIL, "helper", false, true));
        when(permissions.hasPermission(eq(server), eq("helper"), anyString())).thenAnswer(call ->
            granted.stream().anyMatch(permission -> RoleAuthorization.permissionImplies(permission, call.getArgument(2))));
    }

    private static TicketResponse ticketWithNote() {
        TicketNote note = TicketNote.builder().text("internal").issuerName("Mod").date(Instant.EPOCH).build();
        return new TicketResponse("SUPPORT-1", "support", "Support", "Help", "open", null, "Steve", null, "Steve", null, null,
            Instant.EPOCH, false, List.of(), List.of(note), List.of(), Map.of(), Map.of(), List.of(), null, false, false,
            null, List.of(), null);
    }
}
