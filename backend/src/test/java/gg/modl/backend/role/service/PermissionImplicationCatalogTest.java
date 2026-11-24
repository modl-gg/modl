package gg.modl.backend.role.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gg.modl.backend.role.data.Permission;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PermissionImplicationCatalogTest {
    @Test
    void theCatalogParentLinksThatThePanelAndThePluginReadMatchTheBackendImplicationRule() {
        List<Permission> catalog = new PermissionService(mock(StaffRoleRepository.class), mock(PunishmentTypeService.class),
            mock(StaffRepository.class)).getBasePermissions();
        Map<String, Permission> byId = catalog.stream().collect(Collectors.toMap(Permission::id, Function.identity()));

        for (Permission held : catalog) {
            for (Permission requested : catalog) {
                assertThat(RoleAuthorization.permissionImplies(held.id(), requested.id()))
                    .as(held.id() + " -> " + requested.id())
                    .isEqualTo(linkedThroughParents(byId, held.id(), requested.id()));
            }
        }
    }

    private static boolean linkedThroughParents(Map<String, Permission> byId, String held, String requested) {
        for (String current = requested; current != null; current = byId.get(current).parentId()) {
            if (current.equals(held)) {
                return true;
            }
        }
        return false;
    }
}
