package gg.modl.backend.ticket.repository;

import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_ASSIGNEES;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static gg.modl.backend.database.jooq.Tables.TICKET_TAGS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.FreeText;
import gg.modl.backend.infrastructure.util.CanonicalAliasIndex;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.impl.DSL;

final class TicketConditions {
    private static final String FILTER_ALL = "all";
    private static final String ASSIGNEE_NONE = "none";

    private TicketConditions() {
    }

    static boolean hasFreeText(TicketSearchFilter filter) {
        return isPresent(filter.search()) || isPresent(filter.author());
    }

    static Condition search(TicketSearchFilter filter) {
        return matching(filter, true);
    }

    static Condition counted(TicketSearchFilter filter) {
        return matching(filter, false);
    }

    static Condition notUnfinished() {
        return TICKETS.STATUS.ne(TicketStatus.UNFINISHED.getId());
    }

    static Condition status(@Nullable String status) {
        return present(status)
            .filter(value -> !FILTER_ALL.equalsIgnoreCase(value))
            .map(value -> TICKETS.STATUS.eq(TicketStatus.fromCanonicalId(value).getId()))
            .orElseGet(DSL::noCondition);
    }

    static Condition minecraftType(@Nullable String type) {
        return present(type)
            .map(TicketConditions::type)
            .orElseGet(() -> TICKETS.TYPE.in(
                TicketCategory.SUPPORT.getId(), TicketCategory.BUG.getId(), TicketCategory.APPEAL.getId()));
    }

    private static Condition matching(TicketSearchFilter filter, boolean includeReplies) {
        return DSL.and(
            lifecycle(filter.status()),
            text(filter.search(), includeReplies),
            types(filter.types()),
            author(filter.author()),
            labels(filter.labels()),
            assignees(filter.assignees()));
    }

    private static Condition lifecycle(@Nullable String rawStatus) {
        return requestedStatus(rawStatus)
            .map(status -> status == TicketStatus.UNFINISHED
                ? TICKETS.STATUS.eq(status.getId())
                : notUnfinished().and(TICKETS.IS_LOCKED.eq(status.isTerminal())))
            .orElseGet(TicketConditions::notUnfinished);
    }

    private static Condition text(@Nullable String term, boolean includeReplies) {
        return present(term).map(value -> matchingText(value, includeReplies)).orElseGet(DSL::noCondition);
    }

    private static Condition matchingText(String term, boolean includeReplies) {
        Condition header = FreeText.contains(TICKETS.ID, term)
            .or(FreeText.contains(TICKETS.SUBJECT, term))
            .or(FreeText.contains(TICKETS.CREATOR_NAME, term));
        if (!includeReplies) {
            return header;
        }
        return header.or(DSL.exists(DSL.selectOne().from(TICKET_REPLIES)
            .where(TICKET_REPLIES.TICKET_ID.eq(TICKETS.ID))
            .and(FreeText.contains(TICKET_REPLIES.AUTHOR_NAME, term).or(FreeText.contains(TICKET_REPLIES.CONTENT, term)))));
    }

    private static Condition types(@Nullable List<String> types) {
        if (types == null) {
            return DSL.noCondition();
        }
        List<Condition> alternatives = types.stream()
            .filter(type -> isPresent(type) && !FILTER_ALL.equals(type))
            .map(TicketConditions::type)
            .toList();
        return alternatives.isEmpty() ? DSL.noCondition() : DSL.or(alternatives);
    }

    private static Condition type(String rawType) {
        String normalized = CanonicalAliasIndex.normalize(rawType);
        if (!TicketCategory.isCanonicalBucket(normalized)) {
            Optional<TicketCategory> category = category(rawType);
            if (category.isPresent()) {
                return TICKETS.TYPE.eq(category.get().getId());
            }
        }
        List<String> bucket = TicketCategory.categoryIdsForBucket(normalized);
        return bucket.isEmpty() ? TICKETS.TYPE.eq(normalized) : TICKETS.TYPE.in(bucket);
    }

    private static Condition author(@Nullable String author) {
        return present(author).map(value -> FreeText.contains(TICKETS.CREATOR_NAME, value)).orElseGet(DSL::noCondition);
    }

    private static Condition labels(@Nullable List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return DSL.noCondition();
        }
        return DSL.and(labels.stream()
            .map(label -> DSL.exists(DSL.selectOne().from(TICKET_TAGS)
                .where(TICKET_TAGS.TICKET_ID.eq(TICKETS.ID))
                .and(ColumnLookups.matches(TICKET_TAGS.TAG, label))))
            .toList());
    }

    private static Condition assignees(@Nullable List<String> assignees) {
        if (assignees == null) {
            return DSL.noCondition();
        }
        List<Condition> alternatives = new ArrayList<>();
        for (String assignee : assignees) {
            if (!isPresent(assignee)) {
                continue;
            }
            if (ASSIGNEE_NONE.equalsIgnoreCase(assignee)) {
                alternatives.add(DSL.notExists(DSL.selectOne().from(TICKET_ASSIGNEES)
                    .where(TICKET_ASSIGNEES.TICKET_ID.eq(TICKETS.ID))));
                continue;
            }
            String normalized = TicketAssigneeUtil.normalizeSingle(assignee);
            if (normalized != null) {
                alternatives.add(DSL.exists(DSL.selectOne().from(TICKET_ASSIGNEES)
                    .where(TICKET_ASSIGNEES.TICKET_ID.eq(TICKETS.ID))
                    .and(ColumnLookups.matches(TICKET_ASSIGNEES.ASSIGNEE, normalized))));
            }
        }
        return alternatives.isEmpty() ? DSL.noCondition() : DSL.or(alternatives);
    }

    private static Optional<TicketStatus> requestedStatus(@Nullable String rawStatus) {
        return present(rawStatus)
            .filter(value -> !FILTER_ALL.equalsIgnoreCase(value))
            .flatMap(TicketConditions::knownStatus);
    }

    private static Optional<TicketStatus> knownStatus(String rawStatus) {
        try {
            return Optional.of(TicketStatus.fromCanonicalId(rawStatus));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static Optional<TicketCategory> category(String rawType) {
        try {
            return Optional.of(TicketCategory.fromCanonicalId(rawType));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static boolean isPresent(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static Optional<String> present(@Nullable String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
