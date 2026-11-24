package gg.modl.backend.hostedimport.repository;

import static gg.modl.backend.database.jooq.Tables.INVITATIONS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.TICKETS;

import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqImportTargetRepository implements ImportTargetRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;

    public JooqImportTargetRepository(DSLContext dsl, UnitOfWork unitOfWork) {
        this.dsl = dsl;
        this.unitOfWork = unitOfWork;
    }

    @Override
    public boolean hasPunishments() {
        return unitOfWork.read(() -> dsl.fetchExists(dsl.selectOne().from(PUNISHMENTS)));
    }

    @Override
    public boolean hasArticlesBesidesStarters() {
        return unitOfWork.read(() -> dsl.fetchExists(dsl.selectOne().from(KNOWLEDGEBASE_ARTICLES)
            .where(KNOWLEDGEBASE_ARTICLES.IS_STARTER.eq(false))));
    }

    @Override
    public boolean hasStaffOtherThan(String ownerEmail) {
        return unitOfWork.read(() -> dsl.fetchExists(dsl.selectOne().from(STAFF).where(STAFF.EMAIL.ne(ownerEmail))));
    }

    @Override
    public boolean hasActiveInvitations(Instant now) {
        return unitOfWork.read(() -> dsl.fetchExists(dsl.selectOne().from(INVITATIONS).where(INVITATIONS.EXPIRES_AT.gt(now))));
    }

    @Override
    public Set<String> existingTicketIds(Collection<String> ticketIds) {
        List<String> storable = ticketIds.stream().filter(id -> TextLimits.canMatch(TICKETS.ID, id)).distinct().toList();
        return unitOfWork.read(() -> new HashSet<>(InClauseChunks.collect(storable,
            chunk -> dsl.select(TICKETS.ID).from(TICKETS).where(TICKETS.ID.in(chunk)).fetch(TICKETS.ID))));
    }

    @Override
    public Set<String> roleNamesLowerExcept(Collection<String> replacedRoleIds) {
        Set<String> replaced = Set.copyOf(replacedRoleIds);
        Set<String> names = new HashSet<>();
        unitOfWork.read(() -> dsl.select(STAFF_ROLES.ID, STAFF_ROLES.NAME_LOWER).from(STAFF_ROLES).fetch())
            .forEach(row -> {
                if (!replaced.contains(row.value1())) {
                    names.add(row.value2());
                }
            });
        return names;
    }
}
