package gg.modl.backend.ticket.service;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import org.jspecify.annotations.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

public enum TicketChange {
    REPLY(PermissionService.TICKET_REPLY_ALL),
    ASSIGNMENT(PermissionService.TICKET_REPLY_ALL),
    DETAILS(PermissionService.TICKET_REPLY_ALL),
    STATUS(PermissionService.TICKET_CLOSE_ALL),
    VISIBILITY(PermissionService.TICKET_MANAGE_HIDE),
    TAGS(PermissionService.TICKET_MANAGE_TAGS),
    NOTE(PermissionService.TICKET_REPLY_ALL_NOTES);

    private final String permission;

    TicketChange(String permission) {
        this.permission = permission;
    }

    public String permission() {
        return permission;
    }

    public static Set<TicketChange> requestedBy(UpdateTicketRequest request) {
        Set<TicketChange> changes = EnumSet.noneOf(TicketChange.class);
        if (request.newReply() != null) {
            changes.add(REPLY);
        }
        if (request.assignedTo() != null) {
            changes.add(ASSIGNMENT);
        }
        if (request.data() != null) {
            changes.add(DETAILS);
        }
        if (request.status() != null || request.locked() != null) {
            changes.add(STATUS);
        }
        if (request.hidden() != null) {
            changes.add(VISIBILITY);
        }
        if (request.tags() != null) {
            changes.add(TAGS);
        }
        if (request.newNote() != null) {
            changes.add(NOTE);
        }
        return changes;
    }

    public static Set<TicketChange> requestedBy(BulkTicketUpdateRequest request) {
        Set<TicketChange> changes = EnumSet.noneOf(TicketChange.class);
        if (request.locked() != null) {
            changes.add(STATUS);
        }
        if (request.hidden() != null) {
            changes.add(VISIBILITY);
        }
        if (hasAny(request.addLabels()) || hasAny(request.removeLabels())) {
            changes.add(TAGS);
        }
        return changes;
    }

    public static Set<TicketChange> requestedBy(QuickResponseSettings.Action action) {
        return Boolean.TRUE.equals(action.getCloseTicket()) ? EnumSet.of(STATUS) : EnumSet.noneOf(TicketChange.class);
    }

    private static boolean hasAny(@Nullable Collection<String> values) {
        return values != null && !values.isEmpty();
    }
}
