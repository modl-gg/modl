package gg.modl.backend.settings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.settings.data.PunishmentType;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PunishmentTypeEditorTest {
    private static final int CHAT_ABUSE = 6;
    private static final int MANUAL_BAN = 2;

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private PunishmentTypeCatalog catalog;
    private PunishmentTypeEditor editor;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        catalog = kit.catalog();
        editor = new PunishmentTypeEditor(catalog, kit.timestamps(), kit.unitOfWork());
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void aRetriedCreateAddsTheTypeOnceAndPublishesOnce() {
        editor.createPunishmentType(kit.server(), custom("Griefing"));

        assertThat(catalog.snapshot().types()).extracting(PunishmentType::getName).containsOnlyOnce("Griefing");
        assertThat(catalog.snapshot().deletedByOrdinal()).isEmpty();
        verify(kit.dispatcher(), times(1)).publish(any(RealtimeOutboundEvent.class));
        assertThat(kit.server().getPunishmentTypesUpdatedAt()).isNotNull();
    }

    @Test
    void aRenameKeepsTheOrdinalThatItsApplyPermissionIsKeyedBy() {
        int ordinal = editor.createPunishmentType(kit.server(), custom("Griefing")).getOrdinal();

        PunishmentType renamed = editor.updatePunishmentType(kit.server(), ordinal, custom("Vandalism"));

        assertThat(renamed.getOrdinal()).isEqualTo(ordinal);
        assertThat(kit.catalog().snapshot().nameOf(ordinal)).isEqualTo("Vandalism");
        assertThat(kit.server().getStaffPermissionsUpdatedAt()).isNull();
    }

    @Test
    void aTypeCannotTakeTheNameOfAnotherTypeInAnySpelling() {
        int ordinal = editor.createPunishmentType(kit.server(), custom("Griefing")).getOrdinal();

        for (String taken : new String[] {"Blacklist", "blacklist", "manual-ban", "MANUAL  BAN", " Chat_Abuse "}) {
            assertThatThrownBy(() -> editor.updatePunishmentType(kit.server(), ordinal, custom(taken)))
                .as(taken).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> editor.createPunishmentType(kit.server(), custom(taken)))
                .as(taken).isInstanceOf(ConflictException.class);
        }
        assertThat(kit.catalog().snapshot().nameOf(ordinal)).isEqualTo("Griefing");
        assertThat(editor.updatePunishmentType(kit.server(), ordinal, custom("griefing")).getName()).isEqualTo("griefing");
    }

    @Test
    void aBlankNameKeepsTheCurrentName() {
        int ordinal = editor.createPunishmentType(kit.server(), custom("Griefing")).getOrdinal();

        PunishmentType saved = editor.updatePunishmentType(kit.server(), ordinal, custom(" "));

        assertThat(saved.getName()).isEqualTo("Griefing");
    }

    @Test
    void aBuiltInTypeKeepsItsNameAndCategory() {
        PunishmentType saved = editor.updatePunishmentType(kit.server(), MANUAL_BAN,
            custom("Not a ban").toBuilder().staffDescription("Changed").build());

        assertThat(saved.getName()).isEqualTo("Manual Ban");
        assertThat(saved.getCategory()).isEqualTo("Administrative");
        assertThat(saved.getStaffDescription()).isEqualTo("Changed");
    }

    @Test
    void kickBlacklistAndCoreTypesCannotBeReconfiguredOrDeleted() {
        assertThatThrownBy(() -> editor.updatePunishmentType(kit.server(), 0, custom("Kick")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.updatePunishmentType(kit.server(), 5, custom("Blacklist")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.deletePunishmentType(kit.server(), MANUAL_BAN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatingAMissingTypeIsNotReportedAsSaved() {
        assertThatThrownBy(() -> editor.updatePunishmentType(kit.server(), 99, custom("Ghost")))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(kit.settingsRepository().find(PunishmentTypeCatalog.TYPES_SETTINGS_TYPE)).isEmpty();
    }

    @Test
    void deletingTwiceReportsTheSecondAsMissing() {
        assertThat(editor.deletePunishmentType(kit.server(), CHAT_ABUSE)).isTrue();
        assertThat(editor.deletePunishmentType(kit.server(), CHAT_ABUSE)).isFalse();
    }

    private static PunishmentType custom(String name) {
        return PunishmentType.builder().name(name).category("Gameplay").build();
    }
}
