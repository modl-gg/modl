package gg.modl.backend.role.repository;

import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLE_PERMISSIONS;

import gg.modl.backend.database.ColumnLookups;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.InClauseChunks;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitFrame;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.jooq.tables.records.StaffRolePermissionsRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRolesRecord;
import gg.modl.backend.role.data.StaffRole;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqStaffRoleRepository implements StaffRoleRepository {
    private final DSLContext dsl;
    private final UnitOfWork unitOfWork;
    private final InsertIfAbsent insertIfAbsent;
    private final DatabaseDialect dialect;

    @Override
    public List<StaffRole> findAllOrdered() {
        return fetchRoles(DSL.noCondition());
    }

    @Override
    public Optional<StaffRole> findById(String roleId) {
        return fetchRoles(hasId(roleId)).stream().findFirst();
    }

    @Override
    public Optional<StaffRole> findByIdForUpdate(String roleId) {
        UnitFrame.requireWrite();
        lockRoles(hasId(roleId));
        return findById(roleId);
    }

    @Override
    public List<StaffRole> findByIds(Collection<String> roleIds) {
        List<String> ids = ColumnLookups.matchable(STAFF_ROLES.ID, roleIds);
        return InClauseChunks.<String, StaffRole>collect(ids, chunk -> fetchRoles(STAFF_ROLES.ID.in(chunk)));
    }

    @Override
    public Optional<StaffRole> findByNameIgnoreCase(String roleName) {
        return fetchRoles(hasName(roleName)).stream().findFirst();
    }

    @Override
    public Optional<StaffRole> findHighestOrdered() {
        return dsl.select(STAFF_ROLES.ID)
            .from(STAFF_ROLES)
            .orderBy(STAFF_ROLES.SORT_ORDER.desc(), STAFF_ROLES.ID.desc())
            .limit(1)
            .fetchOptional(STAFF_ROLES.ID)
            .flatMap(this::findById);
    }

    @Override
    public List<StaffRole> findCustomRolesWithOrderZero() {
        return fetchRoles(STAFF_ROLES.IS_DEFAULT.isFalse().and(STAFF_ROLES.SORT_ORDER.eq(0)));
    }

    @Override
    public boolean existsByNameIgnoreCase(String roleName) {
        return dsl.fetchExists(STAFF_ROLES, hasName(roleName));
    }

    @Override
    public boolean existsByNameIgnoreCaseExcludingId(String roleName, String excludedRoleId) {
        return dsl.fetchExists(STAFF_ROLES,
            hasName(roleName).and(ColumnLookups.differsFrom(STAFF_ROLES.ID, excludedRoleId)));
    }

    @Override
    public void save(StaffRole role) {
        unitOfWork.write(() -> {
            StaffRolesRecord row = toRow(role);
            int updated = dsl.update(STAFF_ROLES)
                .set(STAFF_ROLES.NAME, row.getName())
                .set(STAFF_ROLES.NAME_LOWER, row.getNameLower())
                .set(STAFF_ROLES.DESCRIPTION, row.getDescription())
                .set(STAFF_ROLES.UPDATED_AT, row.getUpdatedAt())
                .where(STAFF_ROLES.ID.eq(row.getId()))
                .execute();
            if (updated == 0) {
                dsl.insertInto(STAFF_ROLES).set(row).execute();
            } else {
                dsl.deleteFrom(STAFF_ROLE_PERMISSIONS).where(STAFF_ROLE_PERMISSIONS.ROLE_ID.eq(row.getId())).execute();
            }
            insertPermissions(row.getId(), role.getPermissions());
        });
    }

    @Override
    public boolean insertIfAbsent(StaffRole role) {
        return unitOfWork.write(() -> {
            StaffRolesRecord row = toRow(role);
            if (!insertIfAbsent.onAnyUnique(STAFF_ROLES).insert(row)) {
                return false;
            }
            insertPermissions(row.getId(), role.getPermissions());
            return true;
        });
    }

    @Override
    public void updateOrder(Map<String, Integer> orderById) {
        unitOfWork.write(() -> {
            new TreeMap<>(orderById).forEach((id, order) -> dsl.update(STAFF_ROLES)
                .set(STAFF_ROLES.SORT_ORDER, order)
                .where(hasId(id))
                .execute());
        });
    }

    @Override
    public void repairOrderFromZero(Map<String, Integer> orderById) {
        unitOfWork.write(() -> {
            new TreeMap<>(orderById).forEach((id, order) -> dsl.update(STAFF_ROLES)
                .set(STAFF_ROLES.SORT_ORDER, order)
                .where(hasId(id).and(STAFF_ROLES.SORT_ORDER.eq(0)).and(STAFF_ROLES.IS_DEFAULT.isFalse()))
                .execute());
        });
    }

    @Override
    public boolean deleteById(String roleId) {
        return unitOfWork.write(() -> dsl.deleteFrom(STAFF_ROLES).where(hasId(roleId)).execute() == 1);
    }

    private void lockRoles(Condition condition) {
        if (dialect.isExternal()) {
            dsl.select(STAFF_ROLES.ID).from(STAFF_ROLES).where(condition).orderBy(STAFF_ROLES.ID).forUpdate().fetch();
        }
    }

    private List<StaffRole> fetchRoles(Condition condition) {
        Map<String, StaffRole> rolesById = new LinkedHashMap<>();
        for (Record row : dsl.select()
            .from(STAFF_ROLES)
            .leftJoin(STAFF_ROLE_PERMISSIONS).on(STAFF_ROLE_PERMISSIONS.ROLE_ID.eq(STAFF_ROLES.ID))
            .where(condition)
            .orderBy(STAFF_ROLES.SORT_ORDER, STAFF_ROLES.CREATED_AT, STAFF_ROLES.ID, STAFF_ROLE_PERMISSIONS.PERMISSION)
            .fetch()) {
            StaffRole role = rolesById.computeIfAbsent(row.get(STAFF_ROLES.ID), id -> toRole(id, row));
            String permission = row.get(STAFF_ROLE_PERMISSIONS.PERMISSION);
            if (permission != null) {
                role.getPermissions().add(permission);
            }
        }
        return List.copyOf(rolesById.values());
    }

    private void insertPermissions(String roleId, List<String> permissions) {
        if (permissions == null) {
            return;
        }
        List<StaffRolePermissionsRecord> rows = permissions.stream()
            .map(permission -> TextLimits.require(STAFF_ROLE_PERMISSIONS.PERMISSION, permission))
            .distinct()
            .sorted()
            .map(permission -> new StaffRolePermissionsRecord(roleId, permission))
            .toList();
        for (List<StaffRolePermissionsRecord> chunk : InClauseChunks.<StaffRolePermissionsRecord>of(rows)) {
            dsl.batchInsert(chunk).execute();
        }
    }

    private static Condition hasId(String roleId) {
        return ColumnLookups.matches(STAFF_ROLES.ID, roleId);
    }

    private static Condition hasName(String roleName) {
        return ColumnLookups.matches(STAFF_ROLES.NAME_LOWER, lowerCase(roleName));
    }

    private static String lowerCase(String roleName) {
        int[] lowered = roleName.codePoints().map(Character::toLowerCase).toArray();
        return new String(lowered, 0, lowered.length);
    }

    private static StaffRolesRecord toRow(StaffRole role) {
        StaffRolesRecord row = new StaffRolesRecord();
        row.setId(TextLimits.require(STAFF_ROLES.ID, role.getId()));
        row.setName(TextLimits.require(STAFF_ROLES.NAME, role.getName()));
        row.setNameLower(TextLimits.require(STAFF_ROLES.NAME_LOWER, lowerCase(role.getName())));
        row.setDescription(role.getDescription());
        row.setIsDefault(role.isDefault());
        row.setSortOrder(role.getOrder());
        row.setCreatedAt(role.getCreatedAt());
        row.setUpdatedAt(role.getUpdatedAt());
        return row;
    }

    private static StaffRole toRole(String id, Record row) {
        return StaffRole.builder()
            .id(id)
            .name(row.get(STAFF_ROLES.NAME))
            .description(row.get(STAFF_ROLES.DESCRIPTION))
            .permissions(new ArrayList<>())
            .isDefault(row.get(STAFF_ROLES.IS_DEFAULT))
            .order(row.get(STAFF_ROLES.SORT_ORDER))
            .createdAt(row.get(STAFF_ROLES.CREATED_AT))
            .updatedAt(row.get(STAFF_ROLES.UPDATED_AT))
            .build();
    }
}
