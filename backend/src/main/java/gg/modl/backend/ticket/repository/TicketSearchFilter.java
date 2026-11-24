package gg.modl.backend.ticket.repository;

import org.jspecify.annotations.Nullable;
import java.util.List;

public record TicketSearchFilter(
    @Nullable String search,
    @Nullable String status,
    List<String> types,
    @Nullable String author,
    List<String> labels,
    List<String> assignees
) {
}
