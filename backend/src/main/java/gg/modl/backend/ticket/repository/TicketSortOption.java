package gg.modl.backend.ticket.repository;

import org.jspecify.annotations.Nullable;

public enum TicketSortOption {
    NEWEST, OLDEST, RECENTLY_UPDATED, LEAST_RECENTLY_UPDATED;

    public static TicketSortOption from(@Nullable String rawSort) {
        if (rawSort == null) {
            return NEWEST;
        }
        return switch (rawSort) {
            case "oldest" -> OLDEST;
            case "recently-updated" -> RECENTLY_UPDATED;
            case "least-recently-updated" -> LEAST_RECENTLY_UPDATED;
            default -> NEWEST;
        };
    }
}
