package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.ticket.data.TicketCategory;

public final class TicketCategories {
    static final String APPEAL_NEEDS_PUNISHMENT = "APPEAL_NEEDS_PUNISHMENT";

    private TicketCategories() {
    }

    public static void requireOpenableAsTicket(TicketCategory category) {
        if (category == TicketCategory.APPEAL) {
            throw new ValidationException("Appeals are opened from the appeal page of a punishment", APPEAL_NEEDS_PUNISHMENT);
        }
    }
}
