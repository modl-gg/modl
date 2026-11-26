package gg.modl.backend.replay.repository;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static gg.modl.backend.database.jooq.Tables.REPLAY_SHARES;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.ReplaySharesRecord;
import gg.modl.backend.replay.data.ReplayShare;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqReplayShareRepository implements ReplayShareRepository {
    private final DSLContext dsl;
    private final ChildRows childRows;

    @Override
    public boolean insertWithinLimit(ReplayShare share, int openLimit, int closedKept, Instant now) {
        childRows.lockRow(REPLAYS, REPLAYS.ID, TextLimits.require(REPLAYS.ID, share.replayId()));
        Condition ofReplay = REPLAY_SHARES.REPLAY_ID.eq(share.replayId());
        Condition open = openAt(now);
        if (dsl.fetchCount(REPLAY_SHARES, ofReplay.and(open)) >= openLimit) {
            return false;
        }
        insert(share);
        List<String> pruned = dsl.select(REPLAY_SHARES.ID).from(REPLAY_SHARES)
            .where(ofReplay.andNot(open))
            .orderBy(REPLAY_SHARES.CREATED_AT.desc(), REPLAY_SHARES.ID.desc())
            .offset(closedKept)
            .fetch(REPLAY_SHARES.ID);
        InClauseChunks.sum(pruned, chunk -> dsl.deleteFrom(REPLAY_SHARES).where(REPLAY_SHARES.ID.in(chunk)).execute());
        return true;
    }

    private void insert(ReplayShare share) {
        ReplaySharesRecord row = new ReplaySharesRecord();
        row.setId(TextLimits.require(REPLAY_SHARES.ID, share.id()));
        row.setReplayId(TextLimits.require(REPLAY_SHARES.REPLAY_ID, share.replayId()));
        row.setTokenHash(TextLimits.require(REPLAY_SHARES.TOKEN_HASH, share.tokenHash()));
        row.setCreatedBy(TextLimits.require(REPLAY_SHARES.CREATED_BY, share.createdBy()));
        row.setCreatedAt(share.createdAt());
        row.setExpiresAt(share.expiresAt());
        row.setRevokedAt(share.revokedAt());
        row.setViewCount(share.viewCount());
        row.setLastViewedAt(share.lastViewedAt());
        dsl.insertInto(REPLAY_SHARES).set(row).execute();
    }

    @Override
    public List<ReplayShare> listForReplay(String replayId) {
        if (!TextLimits.canMatch(REPLAY_SHARES.REPLAY_ID, replayId)) {
            return List.of();
        }
        return dsl.selectFrom(REPLAY_SHARES)
            .where(REPLAY_SHARES.REPLAY_ID.eq(replayId))
            .orderBy(REPLAY_SHARES.CREATED_AT.desc(), REPLAY_SHARES.ID.desc())
            .fetch(JooqReplayShareRepository::toShare);
    }

    @Override
    public Optional<ReplayShare> findForReplay(String replayId, String shareId) {
        if (!TextLimits.canMatch(REPLAY_SHARES.REPLAY_ID, replayId) || !TextLimits.canMatch(REPLAY_SHARES.ID, shareId)) {
            return Optional.empty();
        }
        return dsl.selectFrom(REPLAY_SHARES)
            .where(REPLAY_SHARES.ID.eq(shareId))
            .and(REPLAY_SHARES.REPLAY_ID.eq(replayId))
            .fetchOptional()
            .map(JooqReplayShareRepository::toShare);
    }

    @Override
    public Optional<ReplayShare> findByTokenHash(String tokenHash) {
        if (!TextLimits.canMatch(REPLAY_SHARES.TOKEN_HASH, tokenHash)) {
            return Optional.empty();
        }
        return dsl.selectFrom(REPLAY_SHARES)
            .where(REPLAY_SHARES.TOKEN_HASH.eq(tokenHash))
            .fetchOptional()
            .map(JooqReplayShareRepository::toShare);
    }

    @Override
    public boolean revoke(String shareId, Instant revokedAt) {
        return dsl.update(REPLAY_SHARES)
            .set(REPLAY_SHARES.REVOKED_AT, revokedAt)
            .where(REPLAY_SHARES.ID.eq(shareId))
            .and(REPLAY_SHARES.REVOKED_AT.isNull())
            .execute() == 1;
    }

    @Override
    public void addViews(String shareId, long views, Instant lastViewedAt) {
        dsl.update(REPLAY_SHARES)
            .set(REPLAY_SHARES.VIEW_COUNT, REPLAY_SHARES.VIEW_COUNT.plus(views))
            .set(REPLAY_SHARES.LAST_VIEWED_AT, lastViewedAt)
            .where(REPLAY_SHARES.ID.eq(shareId))
            .execute();
    }

    @Override
    public Set<String> findReplayIdsWithOpenShare(Collection<String> replayIds, Instant now) {
        List<String> matchable = replayIds.stream().filter(id -> TextLimits.canMatch(REPLAY_SHARES.REPLAY_ID, id)).distinct().toList();
        return Set.copyOf(InClauseChunks.<String, String>collect(matchable, chunk -> dsl.selectDistinct(REPLAY_SHARES.REPLAY_ID)
            .from(REPLAY_SHARES)
            .where(REPLAY_SHARES.REPLAY_ID.in(chunk))
            .and(openAt(now))
            .fetch(REPLAY_SHARES.REPLAY_ID)));
    }

    private static Condition openAt(Instant now) {
        return REPLAY_SHARES.REVOKED_AT.isNull()
            .and(REPLAY_SHARES.EXPIRES_AT.isNull().or(REPLAY_SHARES.EXPIRES_AT.gt(now)));
    }

    private static ReplayShare toShare(ReplaySharesRecord row) {
        return new ReplayShare(row.getId(), row.getReplayId(), row.getTokenHash(), row.getCreatedBy(), row.getCreatedAt(),
            row.getExpiresAt(), row.getRevokedAt(), row.getViewCount() == null ? 0L : row.getViewCount(), row.getLastViewedAt());
    }
}
