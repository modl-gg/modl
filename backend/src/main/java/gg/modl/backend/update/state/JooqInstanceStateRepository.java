package gg.modl.backend.update.state;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_STATE;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.jooq.tables.records.InstanceStateRecord;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqInstanceStateRepository implements InstanceStateRepository {
    private static final Condition IS_INSTANCE = INSTANCE_STATE.ID.eq(1);

    private final DSLContext dsl;

    @Override
    public Optional<InstanceState> find() {
        return dsl.selectFrom(INSTANCE_STATE).where(IS_INSTANCE).fetchOptional().map(JooqInstanceStateRepository::toState);
    }

    @Override
    public void scheduleNextCheck(Instant nextCheckAt) {
        dsl.update(INSTANCE_STATE).set(INSTANCE_STATE.NEXT_CHECK_AT, nextCheckAt).where(IS_INSTANCE).execute();
    }

    @Override
    public void recordCheck(CheckRecord check) {
        dsl.update(INSTANCE_STATE)
            .set(INSTANCE_STATE.LAST_CHECK_AT, check.checkedAt())
            .set(INSTANCE_STATE.LAST_CHECK_OUTCOME, TextLimits.require(INSTANCE_STATE.LAST_CHECK_OUTCOME, check.outcome()))
            .set(INSTANCE_STATE.CHECK_FAILURES, check.failures())
            .set(INSTANCE_STATE.NEXT_CHECK_AT, check.nextCheckAt())
            .set(INSTANCE_STATE.STABLE_RELEASE_JSON, check.stable().envelope())
            .set(INSTANCE_STATE.STABLE_ETAG,
                TextLimits.canMatch(INSTANCE_STATE.STABLE_ETAG, check.stable().etag()) ? check.stable().etag() : null)
            .set(INSTANCE_STATE.PRERELEASE_RELEASE_JSON, check.prerelease().envelope())
            .set(INSTANCE_STATE.PRERELEASE_ETAG,
                TextLimits.canMatch(INSTANCE_STATE.PRERELEASE_ETAG, check.prerelease().etag()) ? check.prerelease().etag() : null)
            .where(IS_INSTANCE)
            .execute();
    }

    @Override
    public void updateSettings(boolean checksEnabled, boolean includePrereleases) {
        dsl.update(INSTANCE_STATE)
            .set(INSTANCE_STATE.CHECKS_ENABLED, checksEnabled)
            .set(INSTANCE_STATE.INCLUDE_PRERELEASES, includePrereleases)
            .where(IS_INSTANCE)
            .execute();
    }

    @Override
    public boolean markNotified(String version) {
        String bound = TextLimits.require(INSTANCE_STATE.NOTIFIED_VERSION, version);
        return dsl.update(INSTANCE_STATE)
            .set(INSTANCE_STATE.NOTIFIED_VERSION, bound)
            .where(IS_INSTANCE.and(INSTANCE_STATE.NOTIFIED_VERSION.isNull().or(INSTANCE_STATE.NOTIFIED_VERSION.ne(bound))))
            .execute() == 1;
    }

    @Override
    public void dismiss(String version, String email) {
        dsl.update(INSTANCE_STATE)
            .set(INSTANCE_STATE.DISMISSED_VERSION, TextLimits.require(INSTANCE_STATE.DISMISSED_VERSION, version))
            .set(INSTANCE_STATE.DISMISSED_BY, TextLimits.require(INSTANCE_STATE.DISMISSED_BY, email))
            .where(IS_INSTANCE)
            .execute();
    }

    @Override
    public boolean confirmRunningVersion(String version, Instant at) {
        return dsl.update(INSTANCE_STATE)
            .set(INSTANCE_STATE.VERSION_CHANGED_AT, at)
            .where(IS_INSTANCE.and(INSTANCE_STATE.LAST_RUN_VERSION.eq(TextLimits.require(INSTANCE_STATE.LAST_RUN_VERSION, version))))
            .execute() == 1;
    }

    private static InstanceState toState(InstanceStateRecord row) {
        return new InstanceState(row.getLastRunVersion(), row.getVersionChangedAt(), Boolean.TRUE.equals(row.getChecksEnabled()),
            Boolean.TRUE.equals(row.getIncludePrereleases()), row.getLastCheckAt(), row.getLastCheckOutcome(),
            row.getCheckFailures() == null ? 0 : row.getCheckFailures(), row.getNextCheckAt(),
            new ReleaseChannelState(row.getStableReleaseJson(), row.getStableEtag()),
            new ReleaseChannelState(row.getPrereleaseReleaseJson(), row.getPrereleaseEtag()),
            row.getNotifiedVersion(), row.getDismissedVersion(), row.getDismissedBy());
    }
}
