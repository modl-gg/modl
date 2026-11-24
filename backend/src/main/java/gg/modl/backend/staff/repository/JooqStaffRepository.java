package gg.modl.backend.staff.repository;

import static gg.modl.backend.database.ColumnLookups.differsFrom;
import static gg.modl.backend.database.ColumnLookups.matchable;
import static gg.modl.backend.database.ColumnLookups.matches;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_TICKET_SUBSCRIPTIONS;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitFrame;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.StaffRecord;
import gg.modl.backend.database.jooq.tables.records.StaffTicketSubscriptionsRecord;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.settings.data.SupportedLanguages;
import gg.modl.backend.staff.data.Staff;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SelectConditionStep;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStaffRepository implements StaffRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final JsonColumns jsonColumns;
    private final DatabaseDialect dialect;

    @Override
    public StaffInsertOutcome insert(Staff staff) {
        return unitOfWork.write(() -> {
            StaffRecord row = newRow(staff);
            if (insertIfAbsent.onAnyUnique(STAFF).insert(row)) {
                staff.setId(row.getId());
                return StaffInsertOutcome.INSERTED;
            }
            return classifyConflict(row.getEmail(), row.getUsername());
        });
    }

    private StaffInsertOutcome classifyConflict(String email, String username) {
        List<String> holderEmails = dsl.select(STAFF.EMAIL)
            .from(STAFF)
            .where(STAFF.EMAIL.eq(email).or(STAFF.USERNAME.eq(username)))
            .fetch(STAFF.EMAIL);
        if (holderEmails.contains(email)) {
            return StaffInsertOutcome.EMAIL_TAKEN;
        }
        return holderEmails.isEmpty() ? StaffInsertOutcome.CONFLICT : StaffInsertOutcome.USERNAME_TAKEN;
    }

    @Override
    public boolean updateRole(String staffId, String roleId, Instant updatedAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.ROLE_ID, TextLimits.require(STAFF.ROLE_ID, roleId))
            .set(STAFF.UPDATED_AT, updatedAt)
            .where(matches(STAFF.ID, staffId))
            .execute() == 1);
    }

    @Override
    public boolean updateProfile(String staffId, @Nullable String username, @Nullable String language, @Nullable String dateFormat,
                                 Instant updatedAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.USERNAME, valueOrUnchanged(STAFF.USERNAME, username))
            .set(STAFF.LANGUAGE, valueOrUnchanged(STAFF.LANGUAGE, language))
            .set(STAFF.DATE_FORMAT, valueOrUnchanged(STAFF.DATE_FORMAT, dateFormat))
            .set(STAFF.UPDATED_AT, updatedAt)
            .where(matches(STAFF.ID, staffId))
            .execute() == 1);
    }

    @Override
    public boolean updateEmail(String staffId, String email, Instant updatedAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.EMAIL, TextLimits.require(STAFF.EMAIL, EmailAddressUtil.requireNormalized(email)))
            .set(STAFF.UPDATED_AT, updatedAt)
            .where(matches(STAFF.ID, staffId))
            .execute() == 1);
    }

    @Override
    public boolean updateMinecraftAssignment(String staffId, @Nullable String uuid, @Nullable String username, Instant updatedAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.ASSIGNED_MINECRAFT_UUID, storedMinecraftUuid(uuid))
            .set(STAFF.ASSIGNED_MINECRAFT_USERNAME, storedMinecraftUsername(username))
            .set(STAFF.UPDATED_AT, updatedAt)
            .where(matches(STAFF.ID, staffId))
            .execute() == 1);
    }

    @Override
    public boolean updateTicketSubscriptionSettings(String staffId, Staff.TicketSubscriptionSettings settings, Instant updatedAt) {
        String json = jsonColumns.write(settings);
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.TICKET_SUBSCRIPTION_SETTINGS_JSON, json)
            .set(STAFF.UPDATED_AT, updatedAt)
            .where(matches(STAFF.ID, staffId))
            .execute() == 1);
    }

    @Override
    public void clearTwoFactor(String staffId) {
        unitOfWork.write(() -> {
            dsl.update(STAFF)
                .setNull(STAFF.TWO_FACTOR_TOKEN_HASH)
                .setNull(STAFF.TWO_FACTOR_TOKEN_IP)
                .setNull(STAFF.TWO_FACTOR_TOKEN_CREATED_AT)
                .set(STAFF.TWO_FACTOR_PENDING_DELIVERY, false)
                .setNull(STAFF.TWO_FACTOR_SESSION_IP)
                .setNull(STAFF.TWO_FACTOR_SESSION_EXPIRES_AT)
                .where(matches(STAFF.ID, staffId))
                .execute();
        });
    }

    @Override
    public boolean deleteById(String staffId) {
        return unitOfWork.write(() -> dsl.deleteFrom(STAFF).where(matches(STAFF.ID, staffId)).execute() == 1);
    }

    @Override
    public long countAll() {
        return dsl.fetchCount(STAFF);
    }

    @Override
    public List<Staff> findAll() {
        return fetchStaff(DSL.noCondition());
    }

    @Override
    public Optional<Staff> findById(String staffId) {
        return fetchOne(matches(STAFF.ID, staffId));
    }

    @Override
    public Optional<Staff> findByIdForUpdate(String staffId) {
        UnitFrame.requireWrite();
        SelectConditionStep<StaffRecord> row = dsl.selectFrom(STAFF).where(matches(STAFF.ID, staffId));
        Optional<StaffRecord> locked = dialect.isExternal() ? row.forUpdate().fetchOptional() : row.fetchOptional();
        return locked.map(this::toStaff);
    }

    @Override
    public Optional<Staff> findByEmail(String email) {
        return fetchOne(matchesEmail(email));
    }

    @Override
    public Optional<Staff> findByUsername(String username) {
        return fetchOne(matches(STAFF.USERNAME, username));
    }

    @Override
    public List<Staff> findByUsernames(Collection<String> usernames) {
        return InClauseChunks.<String, Staff>collect(matchable(STAFF.USERNAME, usernames),
                chunk -> fetchStaff(STAFF.USERNAME.in(chunk)))
            .stream()
            .sorted(Comparator.comparing(Staff::getCreatedAt).thenComparing(Staff::getId))
            .toList();
    }

    @Override
    public boolean existsByEmail(String email) {
        return dsl.fetchExists(STAFF, matchesEmail(email));
    }

    @Override
    public boolean existsByEmailExcluding(String email, String currentEmail) {
        return dsl.fetchExists(STAFF,
            matchesEmail(email).and(differsFrom(STAFF.EMAIL, EmailAddressUtil.normalize(currentEmail))));
    }

    @Override
    public boolean existsByUsername(String username) {
        return dsl.fetchExists(STAFF, matches(STAFF.USERNAME, username));
    }

    @Override
    public boolean existsByUsernameExcludingId(String username, String excludedStaffId) {
        return dsl.fetchExists(STAFF, matches(STAFF.USERNAME, username).and(differsFrom(STAFF.ID, excludedStaffId)));
    }

    @Override
    public boolean existsByEmailOrUsername(String email, String username) {
        return dsl.fetchExists(STAFF, matchesEmail(email).or(matches(STAFF.USERNAME, username)));
    }

    @Override
    public Map<String, String> findUsernamesByIds(Collection<String> staffIds) {
        Map<String, String> usernames = new HashMap<>();
        for (List<String> chunk : InClauseChunks.<String>of(matchable(STAFF.ID, staffIds))) {
            usernames.putAll(dsl.select(STAFF.ID, STAFF.USERNAME)
                .from(STAFF)
                .where(STAFF.ID.in(chunk))
                .fetchMap(STAFF.ID, STAFF.USERNAME));
        }
        return usernames;
    }

    @Override
    public Optional<String> findUsernameByEmail(String email) {
        return dsl.select(STAFF.USERNAME)
            .from(STAFF)
            .where(matchesEmail(email))
            .fetchOptional(STAFF.USERNAME)
            .filter(username -> !username.isBlank());
    }

    @Override
    public List<Staff> findAssignedMinecraftStaff() {
        return fetchStaff(STAFF.ASSIGNED_MINECRAFT_UUID.isNotNull());
    }

    @Override
    public List<String> findAssignedMinecraftUuids() {
        return dsl.select(STAFF.ASSIGNED_MINECRAFT_UUID)
            .from(STAFF)
            .where(STAFF.ASSIGNED_MINECRAFT_UUID.isNotNull())
            .orderBy(STAFF.ASSIGNED_MINECRAFT_UUID)
            .fetch(STAFF.ASSIGNED_MINECRAFT_UUID);
    }

    @Override
    public Optional<Staff> findByAssignedMinecraftUuid(String uuid) {
        return fetchOne(matchesMinecraftUuid(uuid));
    }

    @Override
    public Optional<Staff> findByAssignedMinecraftUuidExcludingId(String uuid, @Nullable String excludedStaffId) {
        return fetchOne(matchesMinecraftUuid(uuid).and(differsFrom(STAFF.ID, excludedStaffId)));
    }

    @Override
    public boolean updateLastSeenByAssignedMinecraftUuid(String uuid, Instant seenAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.LAST_SEEN_AT, seenAt)
            .where(matchesMinecraftUuid(uuid))
            .execute() == 1);
    }

    @Override
    public int countByRoleId(String roleId) {
        return dsl.fetchCount(STAFF, matches(STAFF.ROLE_ID, roleId));
    }

    @Override
    public Map<String, Integer> countByRoleId() {
        Field<Integer> staffCount = DSL.count();
        return dsl.select(STAFF.ROLE_ID, staffCount)
            .from(STAFF)
            .where(STAFF.ROLE_ID.isNotNull())
            .groupBy(STAFF.ROLE_ID)
            .fetchMap(STAFF.ROLE_ID, staffCount);
    }

    @Override
    public boolean createTwoFactorToken(String uuid, String tokenHash, @Nullable String ip, Instant createdAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .set(STAFF.TWO_FACTOR_TOKEN_HASH, TextLimits.require(STAFF.TWO_FACTOR_TOKEN_HASH, tokenHash))
            .set(STAFF.TWO_FACTOR_TOKEN_IP, TextLimits.requireIfPresent(STAFF.TWO_FACTOR_TOKEN_IP, ip))
            .set(STAFF.TWO_FACTOR_TOKEN_CREATED_AT, createdAt)
            .where(matchesMinecraftUuid(uuid))
            .execute() == 1);
    }

    @Override
    public Optional<Staff> findByTwoFactorTokenHash(String tokenHash) {
        return fetchOne(matches(STAFF.TWO_FACTOR_TOKEN_HASH, tokenHash));
    }

    @Override
    public boolean activateTwoFactorSession(String staffId, String tokenHash, @Nullable String sessionIp, Instant sessionExpiresAt) {
        return unitOfWork.write(() -> dsl.update(STAFF)
            .setNull(STAFF.TWO_FACTOR_TOKEN_HASH)
            .setNull(STAFF.TWO_FACTOR_TOKEN_IP)
            .setNull(STAFF.TWO_FACTOR_TOKEN_CREATED_AT)
            .set(STAFF.TWO_FACTOR_PENDING_DELIVERY, true)
            .set(STAFF.TWO_FACTOR_SESSION_IP, TextLimits.requireIfPresent(STAFF.TWO_FACTOR_SESSION_IP, sessionIp))
            .set(STAFF.TWO_FACTOR_SESSION_EXPIRES_AT, sessionExpiresAt)
            .where(matches(STAFF.ID, staffId).and(matches(STAFF.TWO_FACTOR_TOKEN_HASH, tokenHash)))
            .execute() == 1);
    }

    @Override
    public List<Staff> findWithPendingTwoFactorDelivery() {
        return fetchStaff(STAFF.TWO_FACTOR_PENDING_DELIVERY.isTrue().and(STAFF.ASSIGNED_MINECRAFT_UUID.isNotNull()));
    }

    @Override
    public int clearPendingTwoFactorDelivery(Collection<String> staffIds) {
        List<String> ids = matchable(STAFF.ID, staffIds);
        if (ids.isEmpty()) {
            return 0;
        }
        return unitOfWork.write(() -> InClauseChunks.<String>sum(ids, chunk -> dsl.update(STAFF)
            .set(STAFF.TWO_FACTOR_PENDING_DELIVERY, false)
            .where(STAFF.ID.in(chunk).and(STAFF.TWO_FACTOR_PENDING_DELIVERY.isTrue()))
            .execute()));
    }

    @Override
    public void subscribe(String staffEmail, String ticketId, Instant now) {
        if (!TextLimits.canMatch(STAFF_TICKET_SUBSCRIPTIONS.TICKET_ID, ticketId)) {
            return;
        }
        unitOfWork.write(() -> {
            findIdByEmail(staffEmail).ifPresent(staffId -> {
                StaffTicketSubscriptionsRecord row = new StaffTicketSubscriptionsRecord();
                row.setStaffId(staffId);
                row.setTicketId(TextLimits.require(STAFF_TICKET_SUBSCRIPTIONS.TICKET_ID, ticketId));
                row.setSubscribedAt(now);
                insertIfAbsent.onPrimaryKey(STAFF_TICKET_SUBSCRIPTIONS).insert(row);
            });
        });
    }

    @Override
    public boolean unsubscribe(String staffEmail, String ticketId) {
        return unitOfWork.write(() -> findIdByEmail(staffEmail)
            .map(staffId -> dsl.deleteFrom(STAFF_TICKET_SUBSCRIPTIONS)
                .where(matchesSubscription(staffId, ticketId))
                .execute() == 1)
            .orElse(false));
    }

    @Override
    public boolean markSubscriptionRead(String staffEmail, String ticketId, Instant readAt) {
        return unitOfWork.write(() -> findIdByEmail(staffEmail)
            .map(staffId -> dsl.update(STAFF_TICKET_SUBSCRIPTIONS)
                .set(STAFF_TICKET_SUBSCRIPTIONS.LAST_READ_AT, readAt)
                .where(matchesSubscription(staffId, ticketId))
                .execute() == 1)
            .orElse(false));
    }

    @Override
    public List<TicketSubscription> findSubscriptions(String staffEmail) {
        return dsl.select(STAFF_TICKET_SUBSCRIPTIONS.TICKET_ID, STAFF_TICKET_SUBSCRIPTIONS.SUBSCRIBED_AT,
                STAFF_TICKET_SUBSCRIPTIONS.LAST_READ_AT)
            .from(STAFF_TICKET_SUBSCRIPTIONS)
            .join(STAFF).on(STAFF.ID.eq(STAFF_TICKET_SUBSCRIPTIONS.STAFF_ID))
            .where(matchesEmail(staffEmail))
            .orderBy(STAFF_TICKET_SUBSCRIPTIONS.SUBSCRIBED_AT, STAFF_TICKET_SUBSCRIPTIONS.TICKET_ID)
            .fetch(row -> new TicketSubscription(row.value1(), row.value2(), row.value3()));
    }

    private Optional<String> findIdByEmail(String email) {
        return dsl.select(STAFF.ID).from(STAFF).where(matchesEmail(email)).fetchOptional(STAFF.ID);
    }

    private Optional<Staff> fetchOne(Condition condition) {
        return dsl.selectFrom(STAFF).where(condition).fetchOptional().map(this::toStaff);
    }

    private List<Staff> fetchStaff(Condition condition) {
        return dsl.selectFrom(STAFF).where(condition).orderBy(STAFF.CREATED_AT, STAFF.ID).fetch().map(this::toStaff);
    }

    private static Condition matchesEmail(@Nullable String email) {
        return matches(STAFF.EMAIL, EmailAddressUtil.normalize(email));
    }

    private static Condition matchesMinecraftUuid(@Nullable String uuid) {
        return matches(STAFF.ASSIGNED_MINECRAFT_UUID, UuidUtils.normalizeIfPresent(uuid));
    }

    private static Condition matchesSubscription(String staffId, String ticketId) {
        return STAFF_TICKET_SUBSCRIPTIONS.STAFF_ID.eq(staffId).and(matches(STAFF_TICKET_SUBSCRIPTIONS.TICKET_ID, ticketId));
    }

    private StaffRecord newRow(Staff staff) {
        StaffRecord row = new StaffRecord();
        String id = staff.getId();
        row.setId(TextLimits.require(STAFF.ID, id != null ? id : EntityIds.next()));
        row.setEmail(TextLimits.require(STAFF.EMAIL, EmailAddressUtil.requireNormalized(staff.getEmail())));
        row.setUsername(TextLimits.require(STAFF.USERNAME, staff.getUsername()));
        row.setRoleId(TextLimits.requireIfPresent(STAFF.ROLE_ID, staff.getRoleId()));
        row.setAssignedMinecraftUuid(storedMinecraftUuid(staff.getAssignedMinecraftUuid()));
        row.setAssignedMinecraftUsername(storedMinecraftUsername(staff.getAssignedMinecraftUsername()));
        row.setLanguage(storedLanguage(staff.getLanguage()));
        row.setDateFormat(storedDateFormat(staff.getDateFormat()));
        row.setTicketSubscriptionSettingsJson(jsonColumns.writeIfPresent(staff.getTicketSubscriptionSettings()));
        row.setTwoFactorTokenHash(TextLimits.requireIfPresent(STAFF.TWO_FACTOR_TOKEN_HASH, staff.getTwoFactorTokenHash()));
        row.setTwoFactorTokenIp(TextLimits.requireIfPresent(STAFF.TWO_FACTOR_TOKEN_IP, staff.getTwoFactorTokenIp()));
        row.setTwoFactorTokenCreatedAt(instantOf(staff.getTwoFactorTokenCreatedAt()));
        row.setTwoFactorPendingDelivery(staff.isTwoFactorPendingDelivery());
        row.setTwoFactorSessionIp(TextLimits.requireIfPresent(STAFF.TWO_FACTOR_SESSION_IP, staff.getTwoFactorSessionIp()));
        row.setTwoFactorSessionExpiresAt(instantOf(staff.getTwoFactorSessionExpiresAt()));
        row.setLastSeenAt(staff.getLastSeen());
        row.setCreatedAt(staff.getCreatedAt());
        row.setUpdatedAt(staff.getUpdatedAt());
        return row;
    }

    private Staff toStaff(StaffRecord row) {
        return Staff.builder()
            .id(row.getId())
            .email(row.getEmail())
            .username(row.getUsername())
            .roleId(row.getRoleId())
            .assignedMinecraftUuid(row.getAssignedMinecraftUuid())
            .assignedMinecraftUsername(row.getAssignedMinecraftUsername())
            .language(row.getLanguage())
            .dateFormat(row.getDateFormat())
            .ticketSubscriptionSettings(
                jsonColumns.read(row.getTicketSubscriptionSettingsJson(), Staff.TicketSubscriptionSettings.class))
            .twoFactorTokenHash(row.getTwoFactorTokenHash())
            .twoFactorTokenIp(row.getTwoFactorTokenIp())
            .twoFactorTokenCreatedAt(millisOf(row.getTwoFactorTokenCreatedAt()))
            .twoFactorPendingDelivery(Boolean.TRUE.equals(row.getTwoFactorPendingDelivery()))
            .twoFactorSessionIp(row.getTwoFactorSessionIp())
            .twoFactorSessionExpiresAt(millisOf(row.getTwoFactorSessionExpiresAt()))
            .lastSeen(row.getLastSeenAt())
            .createdAt(row.getCreatedAt())
            .updatedAt(row.getUpdatedAt())
            .build();
    }

    private static Field<String> valueOrUnchanged(TableField<?, String> field, @Nullable String value) {
        return value != null ? DSL.val(TextLimits.require(field, value), field) : field;
    }

    @Nullable
    private static String storedMinecraftUuid(@Nullable String uuid) {
        return TextLimits.requireIfPresent(STAFF.ASSIGNED_MINECRAFT_UUID, emptyToNull(UuidUtils.normalizeIfPresent(uuid)));
    }

    @Nullable
    private static String storedMinecraftUsername(@Nullable String username) {
        return TextLimits.requireIfPresent(STAFF.ASSIGNED_MINECRAFT_USERNAME, emptyToNull(username));
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String storedLanguage(@Nullable String language) {
        return TextLimits.require(STAFF.LANGUAGE, Objects.requireNonNullElse(language, SupportedLanguages.DEFAULT));
    }

    private static String storedDateFormat(@Nullable String dateFormat) {
        return TextLimits.require(STAFF.DATE_FORMAT, Objects.requireNonNullElse(dateFormat, Staff.DEFAULT_DATE_FORMAT));
    }

    @Nullable
    private static Instant instantOf(@Nullable Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis);
    }

    @Nullable
    private static Long millisOf(@Nullable Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
