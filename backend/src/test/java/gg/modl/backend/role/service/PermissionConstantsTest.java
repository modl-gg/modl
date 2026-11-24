package gg.modl.backend.role.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gg.modl.backend.role.data.Permission;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PermissionConstantsTest {
    @Test
    void everyCatalogPermissionHasItsOwnConstant() {
        Set<String> constants = Arrays.stream(PermissionService.class.getFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
            .map(PermissionConstantsTest::valueOf)
            .collect(Collectors.toSet());
        PermissionService catalog = new PermissionService(mock(StaffRoleRepository.class), mock(PunishmentTypeService.class),
            mock(StaffRepository.class));

        assertThat(catalog.getBasePermissions()).extracting(Permission::id).allMatch(constants::contains);
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalStateException(inaccessible);
        }
    }
}
