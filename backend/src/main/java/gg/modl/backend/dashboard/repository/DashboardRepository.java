package gg.modl.backend.dashboard.repository;

import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketSummary;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DashboardRepository {
    long countPlayers();

    long countOnlinePlayers();

    long countOnlineAmong(Collection<String> uuids);

    long countPlayersFirstJoinedAfter(Instant after);

    long countPlayersFirstJoinedBetween(Instant from, Instant to);

    long countPunishments();

    long countTickets();

    long countTicketsByStatus(TicketStatus status);

    long countTicketsCreatedAfter(Instant after);

    long countTicketsCreatedBetween(Instant from, Instant to);

    long countUnresolvedTickets();

    long countUnresolvedReports();

    List<TicketSummary> findRecentTickets(int limit);

    List<Ticket> findStaffActivityTickets(StaffIdentity staff, Instant cutoff, int limit);
}
