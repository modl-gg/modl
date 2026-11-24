package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.util.PaginationHelper;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.dto.response.PaginatedTicketsResponse;
import gg.modl.backend.ticket.dto.response.PlayerTicketResponse;
import gg.modl.backend.ticket.dto.response.TicketListItemResponse;
import gg.modl.backend.ticket.repository.TicketCounts;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSearchFilter;
import gg.modl.backend.ticket.repository.TicketSearchPage;
import gg.modl.backend.ticket.repository.TicketSortOption;
import gg.modl.backend.ticket.repository.TicketSummary;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketSearchService {
    private final TicketRepository ticketRepository;

    public PaginatedTicketsResponse searchTickets(Server server, int page, int limit, @Nullable String search,
                                                  @Nullable String status, @Nullable List<String> types,
                                                  @Nullable String author, @Nullable List<String> labels,
                                                  @Nullable List<String> assignees, String sort) {
        TicketSearchPage searchPage = ticketRepository.search(
            filter(search, status, types, author, labels, assignees), TicketSortOption.from(sort), page, limit);

        List<TicketListItemResponse> ticketItems = searchPage.tickets().stream().map(this::toListItemResponse).toList();
        int totalPages = PaginationHelper.calculateTotalPages(searchPage.total(), limit);

        return new PaginatedTicketsResponse(
            ticketItems,
            new PaginatedTicketsResponse.PaginationInfo(
                page,
                totalPages,
                limit,
                searchPage.total(),
                page < totalPages,
                page > 1
            ),
            new PaginatedTicketsResponse.FiltersInfo(search, status, types)
        );
    }

    private static TicketSearchFilter filter(@Nullable String search, @Nullable String status, @Nullable List<String> types,
                                             @Nullable String author, @Nullable List<String> labels,
                                             @Nullable List<String> assignees) {
        return new TicketSearchFilter(search, status, Objects.requireNonNullElse(types, List.of()), author,
            Objects.requireNonNullElse(labels, List.of()), Objects.requireNonNullElse(assignees, List.of()));
    }

    private TicketListItemResponse toListItemResponse(TicketSummary ticket) {
        String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown";
        return new TicketListItemResponse(
            ticket.getId(),
            ticket.getSubject() != null ? ticket.getSubject() : "No Subject",
            ticket.getStatus().getId(),
            creatorName,
            creatorName,
            ticket.getCreatedAt(),
            ticket.getType().getDisplayName(),
            ticket.isLocked(),
            ticket.getType().getId(),
            ticket.getLastReply(),
            ticket.getReplyCount(),
            ticket.getTags(),
            ticket.getAssignedTo(),
            ticket.isHidden()
        );
    }

    public Map<String, Long> getTicketCounts(Server server, @Nullable String search, @Nullable List<String> types,
                                             @Nullable String author, @Nullable List<String> labels,
                                             @Nullable List<String> assignees) {
        TicketCounts counts = ticketRepository.count(filter(search, null, types, author, labels, assignees));
        return Map.of("open", counts.open(), "closed", counts.closed());
    }

    public List<PlayerTicketResponse> getTicketsByPlayer(Server server, String playerUuid) {
        return ticketRepository.findByPlayer(UuidUtils.normalize(playerUuid))
            .stream()
            .map(this::toPlayerTicketResponse)
            .toList();
    }

    private PlayerTicketResponse toPlayerTicketResponse(Ticket ticket) {
        return new PlayerTicketResponse(
            ticket.getId(),
            ticket.getSubject(),
            ticket.getStatus().getId(),
            ticket.getType().getId(),
            ticket.getCreated(),
            ticket.getCreatorName(),
            ticket.getCreatorUuid(),
            ticket.getReportedPlayer(),
            ticket.getReportedPlayerUuid(),
            ticket.isLocked(),
            ticket.getTags(),
            ticket.getAssignedTo()
        );
    }

    public List<PlayerTicketResponse> getTicketsByTag(Server server, String tag) {
        return ticketRepository.findByTag(tag)
            .stream()
            .map(this::toPlayerTicketResponse)
            .toList();
    }
}
