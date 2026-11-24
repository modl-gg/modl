package gg.modl.backend.audit.repository;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;

import gg.modl.backend.database.UnitOfWork;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqAdminTableBrowserRepository implements AdminTableBrowserRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    @Override
    public List<Map<String, Object>> readPage(BrowsableTable table, int limit, int offset) {
        TableView view = viewOf(table);
        return unitOfWork.report(() -> dsl.selectFrom(view.table())
            .orderBy(view.newestFirst())
            .limit(limit)
            .offset(offset)
            .fetchMaps());
    }

    @Override
    public long count(BrowsableTable table) {
        TableView view = viewOf(table);
        return unitOfWork.report(() -> (long) dsl.fetchCount(view.table()));
    }

    static TableView viewOf(BrowsableTable table) {
        return switch (table) {
            case PLAYERS -> new TableView(PLAYERS, List.of(PLAYERS.CREATED_AT.desc(), PLAYERS.ID.desc()));
            case SETTINGS -> new TableView(SETTINGS, List.of(SETTINGS.UPDATED_AT.desc(), SETTINGS.TYPE.desc()));
            case STAFF -> new TableView(STAFF, List.of(STAFF.CREATED_AT.desc(), STAFF.ID.desc()));
            case STAFF_ROLES -> new TableView(STAFF_ROLES, List.of(STAFF_ROLES.CREATED_AT.desc(), STAFF_ROLES.ID.desc()));
            case TICKETS -> new TableView(TICKETS, List.of(TICKETS.CREATED_AT.desc(), TICKETS.ID.desc()));
            case TICKET_VERIFICATIONS -> new TableView(TICKET_VERIFICATIONS,
                List.of(TICKET_VERIFICATIONS.EXPIRES_AT.desc(), TICKET_VERIFICATIONS.ID.desc()));
            case AUDIT_LOGS -> new TableView(AUDIT_LOGS, List.of(AUDIT_LOGS.ID.desc()));
            case KNOWLEDGEBASE_CATEGORIES -> new TableView(KNOWLEDGEBASE_CATEGORIES,
                List.of(KNOWLEDGEBASE_CATEGORIES.CREATED_AT.desc(), KNOWLEDGEBASE_CATEGORIES.ID.desc()));
            case KNOWLEDGEBASE_ARTICLES -> new TableView(KNOWLEDGEBASE_ARTICLES,
                List.of(KNOWLEDGEBASE_ARTICLES.CREATED_AT.desc(), KNOWLEDGEBASE_ARTICLES.ID.desc()));
            case HOMEPAGE_CARDS -> new TableView(HOMEPAGE_CARDS,
                List.of(HOMEPAGE_CARDS.CREATED_AT.desc(), HOMEPAGE_CARDS.ID.desc()));
        };
    }

    record TableView(Table<?> table, List<SortField<?>> newestFirst) {
    }
}
