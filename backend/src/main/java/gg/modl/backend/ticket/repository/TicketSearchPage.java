package gg.modl.backend.ticket.repository;

import java.util.List;

public record TicketSearchPage(List<TicketSummary> tickets, long total) {
}
