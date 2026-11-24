package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentType;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PunishmentTypeServiceTest {
    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PunishmentTypeCatalog catalog;
    private PunishmentTypeService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        catalog = kit.catalog();
        service = new PunishmentTypeService(catalog);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void readingBeforeSeedingShowsTheDefaultsWithoutWritingThem() {
        assertThat(service.getPunishmentTypes(kit.server())).isEqualTo(DefaultPunishmentTypes.getAll());
        assertThat(kit.settingsRepository().find(PunishmentTypeCatalog.TYPES_SETTINGS_TYPE)).isEmpty();
    }

    @Test
    void readsInsideAReadUnitNeverTryToWrite() {
        assertThat(kit.unitOfWork().read(() -> service.getPunishmentTypes(kit.server()))).isNotEmpty();
    }

    @Test
    void aDeletedTypeKeepsItsNameAndAppealabilityButNotItsDefinition() {
        PunishmentTypeEditor editor =
            new PunishmentTypeEditor(catalog, kit.timestamps(), kit.unitOfWork());
        PunishmentType created = editor.createPunishmentType(kit.server(), PunishmentType.builder()
            .name("Griefing").category("Gameplay").appealable(true).customPoints(4).build());
        assertThat(service.getPointsForPunishment(kit.server(), created.getOrdinal(), "regular")).isEqualTo(4);

        editor.deletePunishmentType(kit.server(), created.getOrdinal());

        assertThat(service.getPunishmentTypeName(kit.server(), created.getOrdinal())).isEqualTo("Griefing (deleted)");
        assertThat(service.getPunishmentTypeByOrdinal(kit.server(), created.getOrdinal())).isEmpty();
        assertThat(service.isAppealable(kit.server(), created.getOrdinal())).isTrue();
        assertThat(service.getPointsForPunishment(kit.server(), created.getOrdinal(), "regular")).isZero();
        assertThat(service.calculateDurationMillis(kit.server(), created.getOrdinal(), null, null)).isZero();
    }
}
