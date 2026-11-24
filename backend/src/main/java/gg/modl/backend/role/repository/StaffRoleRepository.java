package gg.modl.backend.role.repository;

import gg.modl.backend.role.data.StaffRole;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StaffRoleRepository {
    List<StaffRole> findAllOrdered();

    Optional<StaffRole> findById(String roleId);

    Optional<StaffRole> findByIdForUpdate(String roleId);

    List<StaffRole> findByIds(Collection<String> roleIds);

    Optional<StaffRole> findByNameIgnoreCase(String roleName);

    Optional<StaffRole> findHighestOrdered();

    List<StaffRole> findCustomRolesWithOrderZero();

    boolean existsByNameIgnoreCase(String roleName);

    boolean existsByNameIgnoreCaseExcludingId(String roleName, String excludedRoleId);

    void save(StaffRole role);

    boolean insertIfAbsent(StaffRole role);

    void updateOrder(Map<String, Integer> orderById);

    void repairOrderFromZero(Map<String, Integer> orderById);

    boolean deleteById(String roleId);
}
