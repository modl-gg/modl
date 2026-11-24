package gg.modl.backend.instance.repository;

import static gg.modl.backend.database.jooq.Tables.SETUP_STEPS;

import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.database.jooq.Tables;
import gg.modl.backend.database.jooq.tables.records.InstanceRecord;
import gg.modl.backend.database.jooq.tables.records.SetupStepsRecord;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqInstanceRepository implements InstanceRepository {
    private static final int SINGLETON_ID = 1;
    private static final Condition IS_INSTANCE = Tables.INSTANCE.ID.eq(SINGLETON_ID);
    private static final Condition IS_TRACKING_SETUP = IS_INSTANCE.and(Tables.INSTANCE.SETUP_STARTED_AT.isNotNull());

    private final DSLContext dsl;
    private final InsertIfAbsent insertIfAbsent;
    private final Upserts upserts;

    @Override
    public Optional<Server> find() {
        return dsl.selectFrom(Tables.INSTANCE)
            .where(IS_INSTANCE)
            .fetchOptional()
            .map(this::toServer);
    }

    @Override
    public Server ensure(String serverName, Instant now) {
        InstanceRecord row = new InstanceRecord();
        row.setId(SINGLETON_ID);
        row.setServerName(TextLimits.require(Tables.INSTANCE.SERVER_NAME, serverName));
        row.setSetupStartedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        insertIfAbsent.onPrimaryKey(Tables.INSTANCE).insert(row);
        return find().orElseThrow(() -> new IllegalStateException("The instance row could not be created"));
    }

    @Override
    public boolean claim(String adminEmail, @Nullable String publicUrl, Instant now) {
        return dsl.update(Tables.INSTANCE)
            .set(Tables.INSTANCE.ADMIN_EMAIL, TextLimits.require(Tables.INSTANCE.ADMIN_EMAIL, adminEmail))
            .set(Tables.INSTANCE.PUBLIC_URL, TextLimits.requireIfPresent(Tables.INSTANCE.PUBLIC_URL, publicUrl))
            .set(Tables.INSTANCE.SETUP_STARTED_AT, DSL.coalesce(Tables.INSTANCE.SETUP_STARTED_AT, DSL.val(now, Tables.INSTANCE.SETUP_STARTED_AT)))
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_INSTANCE.and(isBlank(Tables.INSTANCE.ADMIN_EMAIL)))
            .execute() == 1;
    }

    @Override
    public void updateAdminEmail(String adminEmail, Instant now) {
        updateText(Tables.INSTANCE.ADMIN_EMAIL, adminEmail, now);
    }

    @Override
    public void assignConfiguredAdminEmail(String adminEmail, Instant now) {
        dsl.update(Tables.INSTANCE)
            .set(Tables.INSTANCE.ADMIN_EMAIL, TextLimits.require(Tables.INSTANCE.ADMIN_EMAIL, adminEmail))
            .setNull(Tables.INSTANCE.SETUP_OWNER_SIGNED_IN_AT)
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_INSTANCE)
            .execute();
    }

    @Override
    public boolean recordOwnerSignIn(Instant now) {
        boolean recorded = dsl.update(Tables.INSTANCE)
            .set(Tables.INSTANCE.SETUP_OWNER_SIGNED_IN_AT, now)
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_TRACKING_SETUP.and(Tables.INSTANCE.SETUP_OWNER_SIGNED_IN_AT.isNull()))
            .execute() == 1;
        if (recorded) {
            storeStep(SetupStep.ACCOUNT, SetupStepStatus.DONE, now);
        }
        return recorded;
    }

    @Override
    public void updateSetupStep(SetupStep step, SetupStepStatus status, Instant now) {
        boolean tracked = dsl.update(Tables.INSTANCE)
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_TRACKING_SETUP)
            .execute() == 1;
        if (tracked) {
            storeStep(step, status, now);
        }
    }

    @Override
    public void completeSetup(Instant now) {
        dsl.update(Tables.INSTANCE)
            .set(Tables.INSTANCE.SETUP_COMPLETED_AT, now)
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_TRACKING_SETUP.and(Tables.INSTANCE.SETUP_COMPLETED_AT.isNull()))
            .execute();
    }

    @Override
    public void updateServerName(String serverName, Instant now) {
        updateText(Tables.INSTANCE.SERVER_NAME, serverName, now);
    }

    @Override
    public void updatePublicUrl(@Nullable String publicUrl, Instant now) {
        updateText(Tables.INSTANCE.PUBLIC_URL, publicUrl, now);
    }

    @Override
    public boolean storePublicUrlIfUnset(String publicUrl, Instant now) {
        return dsl.update(Tables.INSTANCE)
            .set(Tables.INSTANCE.PUBLIC_URL, TextLimits.requireIfPresent(Tables.INSTANCE.PUBLIC_URL, publicUrl))
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_INSTANCE.and(isBlank(Tables.INSTANCE.PUBLIC_URL)))
            .execute() == 1;
    }

    @Override
    public void updateApiKey(String apiKey, Instant now) {
        updateText(Tables.INSTANCE.API_KEY, apiKey, now);
    }

    @Override
    public void updateStaffPermissionsTimestamp(Instant timestamp) {
        updateTimestamp(Tables.INSTANCE.STAFF_PERMISSIONS_UPDATED_AT, timestamp);
    }

    @Override
    public void updatePunishmentTypesTimestamp(Instant timestamp) {
        updateTimestamp(Tables.INSTANCE.PUNISHMENT_TYPES_UPDATED_AT, timestamp);
    }

    private void updateText(TableField<InstanceRecord, String> field, @Nullable String value, Instant now) {
        dsl.update(Tables.INSTANCE)
            .set(field, TextLimits.requireIfPresent(field, value))
            .set(Tables.INSTANCE.UPDATED_AT, now)
            .where(IS_INSTANCE)
            .execute();
    }

    private void updateTimestamp(TableField<InstanceRecord, Instant> field, Instant timestamp) {
        dsl.update(Tables.INSTANCE)
            .set(field, timestamp)
            .set(Tables.INSTANCE.UPDATED_AT, timestamp)
            .where(IS_INSTANCE)
            .execute();
    }

    private void storeStep(SetupStep step, SetupStepStatus status, Instant now) {
        String storedStatus = TextLimits.require(SETUP_STEPS.STATUS, status.name());
        SetupStepsRecord row = new SetupStepsRecord(TextLimits.require(SETUP_STEPS.STEP, step.name()), storedStatus, now);
        Map<Field<?>, Object> assignments = Map.of(SETUP_STEPS.STATUS, storedStatus, SETUP_STEPS.UPDATED_AT, now);
        upserts.onPrimaryKey(SETUP_STEPS, row, assignments);
    }

    private Server toServer(InstanceRecord row) {
        Server server = new Server(row.getServerName());
        server.setAdminEmail(row.getAdminEmail());
        server.setApiKey(row.getApiKey());
        server.setPublicUrl(row.getPublicUrl());
        server.setCreatedAt(row.getCreatedAt());
        server.setUpdatedAt(row.getUpdatedAt());
        server.setStaffPermissionsUpdatedAt(row.getStaffPermissionsUpdatedAt());
        server.setPunishmentTypesUpdatedAt(row.getPunishmentTypesUpdatedAt());
        if (row.getSetupStartedAt() != null) {
            server.setSetup(new SetupProgress(
                row.getSetupStartedAt(), row.getSetupOwnerSignedInAt(), row.getSetupCompletedAt(), storedSteps()));
        }
        return server;
    }

    private Map<String, String> storedSteps() {
        return dsl.select(SETUP_STEPS.STEP, SETUP_STEPS.STATUS)
            .from(SETUP_STEPS)
            .fetchMap(SETUP_STEPS.STEP, SETUP_STEPS.STATUS);
    }

    private static Condition isBlank(Field<String> field) {
        return field.isNull().or(field.eq(""));
    }
}
