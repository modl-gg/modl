package gg.modl.backend.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.dto.request.AddNoteRequest;
import gg.modl.backend.ticket.dto.request.AddReplyRequest;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TicketChangeAuthorizerTest {
    private static final String EMAIL = "helper@example.com";

    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final RoleAuthorization roles = mock(RoleAuthorization.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final TicketChangeAuthorizer authorizer = new TicketChangeAuthorizer(roles, permissions);

    @BeforeEach
    void aReplierWithoutTicketManagement() {
        when(roles.panelPerformer(server, EMAIL)).thenReturn(new PerformerAuthority(EMAIL, "helper", false, true));
        when(permissions.hasPermission(server, "helper", PermissionService.TICKET_REPLY_ALL)).thenReturn(true);
    }

    @Test
    void aReplyNeedsNoExtraPermission() {
        assertThatCode(() -> authorizer.requirePermitted(server, EMAIL,
            TicketChange.requestedBy(new UpdateTicketRequest(null, null, null, null, null, null, null, List.of("helper"), "Mod")))).doesNotThrowAnyException();
    }

    @Test
    void closingHidingAndTaggingNeedTheirOwnPermissions() {
        List<UpdateTicketRequest> changes = List.of(
            new UpdateTicketRequest("closed", null, null, null, null, null, null, null, "Mod"),
            new UpdateTicketRequest(null, true, null, null, null, null, null, null, "Mod"),
            new UpdateTicketRequest(null, null, null, null, null, null, true, null, "Mod"),
            new UpdateTicketRequest(null, null, null, null, List.of("spam"), null, null, null, "Mod"));

        for (UpdateTicketRequest change : changes) {
            assertThatThrownBy(() -> authorizer.requirePermitted(server, EMAIL, TicketChange.requestedBy(change))).isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    void anInternalNoteNeedsTheNotePermissionWhichReplyingDoesNotImply() {
        UpdateTicketRequest withNote = new UpdateTicketRequest(null, null, null,
            new AddNoteRequest("internal", null), null, null, null, null, "Mod");

        assertThat(TicketChange.requestedBy(withNote)).containsExactly(TicketChange.NOTE);
        assertThatThrownBy(() -> authorizer.requirePermitted(server, EMAIL, TicketChange.requestedBy(withNote)))
            .isInstanceOf(ForbiddenException.class);

        when(permissions.hasPermission(server, "helper", PermissionService.TICKET_REPLY_ALL_NOTES)).thenReturn(true);
        assertThatCode(() -> authorizer.requirePermitted(server, EMAIL, TicketChange.requestedBy(withNote)))
            .doesNotThrowAnyException();
    }

    @Test
    void aNoteTakerWithoutTheReplyPermissionMayAddNotesButNotReplyAssignOrEditDetails() {
        String noteTaker = "notes@example.com";
        when(roles.panelPerformer(server, noteTaker)).thenReturn(new PerformerAuthority(noteTaker, "notes", false, true));
        when(permissions.hasPermission(server, "notes", PermissionService.TICKET_REPLY_ALL_NOTES)).thenReturn(true);
        UpdateTicketRequest withNote = new UpdateTicketRequest(null, null, null,
            new AddNoteRequest("internal", null), null, null, null, null, "Mod");
        List<UpdateTicketRequest> replierChanges = List.of(
            new UpdateTicketRequest(null, null, new AddReplyRequest("Notes", "hello", null, null), null, null, null, null, null, "Mod"),
            new UpdateTicketRequest(null, null, null, null, null, null, null, List.of("notes"), "Mod"),
            new UpdateTicketRequest(null, null, null, null, null, Map.of("priority", "high"), null, null, "Mod"));

        assertThatCode(() -> authorizer.requirePermitted(server, noteTaker, TicketChange.requestedBy(withNote)))
            .doesNotThrowAnyException();
        for (UpdateTicketRequest change : replierChanges) {
            assertThatThrownBy(() -> authorizer.requirePermitted(server, noteTaker, TicketChange.requestedBy(change)))
                .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    void aClosingQuickResponseNeedsTheClosePermission() {
        QuickResponseSettings.Action closing = QuickResponseSettings.Action.builder().message("Done").closeTicket(true).build();
        QuickResponseSettings.Action replying = QuickResponseSettings.Action.builder().message("Thanks").closeTicket(false).build();

        assertThatThrownBy(() -> authorizer.requirePermitted(server, EMAIL, TicketChange.requestedBy(closing)))
            .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> authorizer.requirePermitted(server, EMAIL, TicketChange.requestedBy(replying)))
            .doesNotThrowAnyException();

        when(permissions.hasPermission(server, "helper", PermissionService.TICKET_CLOSE_ALL)).thenReturn(true);
        assertThatCode(() -> authorizer.requirePermitted(server, EMAIL, TicketChange.requestedBy(closing)))
            .doesNotThrowAnyException();
    }

    @Test
    void bulkHidingAndLabellingNeedTheirOwnPermissions() {
        when(permissions.hasPermission(server, "helper", PermissionService.TICKET_CLOSE_ALL)).thenReturn(true);

        assertThatCode(() -> authorizer.requirePermitted(server, EMAIL,
            TicketChange.requestedBy(new BulkTicketUpdateRequest(List.of("T-1"), true, null, null, null, null)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requirePermitted(server, EMAIL,
            TicketChange.requestedBy(new BulkTicketUpdateRequest(List.of("T-1"), null, null, null, null, true)))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorizer.requirePermitted(server, EMAIL,
            TicketChange.requestedBy(new BulkTicketUpdateRequest(List.of("T-1"), null, null, List.of("spam"), null, null)))).isInstanceOf(ForbiddenException.class);

        when(permissions.hasPermission(server, "helper", PermissionService.TICKET_MANAGE_TAGS)).thenReturn(true);
        assertThatCode(() -> authorizer.requirePermitted(server, EMAIL,
            TicketChange.requestedBy(new BulkTicketUpdateRequest(List.of("T-1"), null, List.of("spam"), null, null, null)))).doesNotThrowAnyException();
    }
}
