package gg.modl.backend.settings.service;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.instance.ParallelCalls.Outcome;
import gg.modl.backend.settings.data.AppealForm;
import gg.modl.backend.settings.data.AppealFormField;
import gg.modl.backend.settings.data.AppealFormSection;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.repository.SettingsRepository;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jooq.Result;
import org.junit.jupiter.api.io.TempDir;

class PunishmentTypeCatalogContractTest {
    private static final int FIRST_CUSTOM_ORDINAL = 18;
    private static final int FIRST_CUSTOM_ID = 20;
    private static final int CHAT_ABUSE = 6;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void seedingStoresTheDefaultsOnceAndReadsThemBackEqual(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeEditor editor = editor(kit, kit.catalog());

            editor.seedDefaultTypes(kit.server());
            editor.seedDefaultTypes(kit.server());

            assertThat(kit.catalog().snapshot().types()).isEqualTo(DefaultPunishmentTypes.getAll());
            assertThat(versionOfTypes(kit)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void resettingUnchangedDefaultsWritesNothing(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeEditor editor = editor(kit, kit.catalog());
            editor.seedDefaultTypes(kit.server());

            editor.resetToDefaults(kit.server());

            assertThat(versionOfTypes(kit)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void twoConcurrentCreatesGetDistinctOrdinalsAndIds(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeEditor editor = editor(kit, kit.catalog());
            editor.seedDefaultTypes(kit.server());

            List<Outcome<PunishmentType>> outcomes = ParallelCalls.run(2,
                caller -> editor.createPunishmentType(kit.server(), custom("Custom " + caller)));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(outcomes.stream().map(outcome -> outcome.value().getOrdinal()).toList())
                .containsExactlyInAnyOrder(FIRST_CUSTOM_ORDINAL, FIRST_CUSTOM_ORDINAL + 1);
            assertThat(outcomes.stream().map(outcome -> outcome.value().getId()).toList())
                .containsExactlyInAnyOrder(FIRST_CUSTOM_ID, FIRST_CUSTOM_ID + 1);
            assertThat(kit.catalog().snapshot().types()).hasSize(DefaultPunishmentTypes.getAll().size() + 2);
        }
    }

    @SqlContractTest
    void resetTombstonesCustomTypesAndTheirNumbersAreNeverReused(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeCatalog catalog = kit.catalog();
            PunishmentTypeEditor editor = editor(kit, catalog);
            assertThat(editor.createPunishmentType(kit.server(), custom("Griefing")).getOrdinal()).isEqualTo(FIRST_CUSTOM_ORDINAL);

            editor.resetToDefaults(kit.server());
            PunishmentType next = editor.createPunishmentType(kit.server(), custom("Spawn Killing"));

            assertThat(next.getOrdinal()).isEqualTo(FIRST_CUSTOM_ORDINAL + 1);
            assertThat(next.getId()).isEqualTo(FIRST_CUSTOM_ID + 1);
            assertThat(catalog.snapshot().nameOf(FIRST_CUSTOM_ORDINAL)).isEqualTo("Griefing (deleted)");
            assertThat(catalog.snapshot().find(FIRST_CUSTOM_ORDINAL)).isEmpty();
        }
    }

    @SqlContractTest
    void deletingTheHighestTypeDoesNotFreeItsOrdinalOrId(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeCatalog catalog = kit.catalog();
            PunishmentTypeEditor editor = editor(kit, catalog);
            PunishmentType first = editor.createPunishmentType(kit.server(), custom("Griefing"));

            assertThat(editor.deletePunishmentType(kit.server(), first.getOrdinal())).isTrue();
            PunishmentType second = editor.createPunishmentType(kit.server(), custom("Scamming"));
            assertThat(editor.deletePunishmentType(kit.server(), second.getOrdinal())).isTrue();
            PunishmentType third = editor.createPunishmentType(kit.server(), custom("Doxxing"));

            assertThat(List.of(first.getOrdinal(), second.getOrdinal(), third.getOrdinal())).containsExactly(18, 19, 20);
            assertThat(List.of(first.getId(), second.getId(), third.getId())).containsExactly(20, 21, 22);
            assertThat(catalog.snapshot().nameOf(18)).isEqualTo("Griefing (deleted)");
            assertThat(catalog.snapshot().nameOf(19)).isEqualTo("Scamming (deleted)");
            assertThat(catalog.snapshot().nameOf(20)).isEqualTo("Doxxing");
            assertThat(catalog.snapshot().nameOf(99)).isEqualTo("Unknown");
        }
    }

    @SqlContractTest
    void aDeletedTypeKeepsTheAppealabilityItHadSoItsBansStayAppealable(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeCatalog catalog = kit.catalog();
            PunishmentTypeEditor editor = editor(kit, catalog);
            PunishmentType appealable = custom("Griefing");
            appealable.setAppealable(true);
            PunishmentType notAppealable = custom("Doxxing");
            notAppealable.setAppealable(false);
            int appealableOrdinal = editor.createPunishmentType(kit.server(), appealable).getOrdinal();
            int notAppealableOrdinal = editor.createPunishmentType(kit.server(), notAppealable).getOrdinal();

            editor.deletePunishmentType(kit.server(), appealableOrdinal);
            editor.deletePunishmentType(kit.server(), notAppealableOrdinal);

            assertThat(catalog.snapshot().isAppealable(appealableOrdinal)).isTrue();
            assertThat(catalog.snapshot().isAppealable(notAppealableOrdinal)).isFalse();
            assertThat(catalog.snapshot().isAppealable(99)).isFalse();
        }
    }

    @SqlContractTest
    void numbersSentByTheCallerNeverDecideTheAllocation(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentType forged = custom("Forged").toBuilder().ordinal(2).id(2).build();

            PunishmentType created = editor(kit, kit.catalog()).createPunishmentType(kit.server(), forged);

            assertThat(created.getOrdinal()).isEqualTo(FIRST_CUSTOM_ORDINAL);
            assertThat(created.getId()).isEqualTo(FIRST_CUSTOM_ID);
        }
    }

    @SqlContractTest
    void aDefaultRestoredByResetKeepsOneTombstoneWhenDeletedAgain(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeCatalog catalog = kit.catalog();
            PunishmentTypeEditor editor = editor(kit, catalog);
            editor.deletePunishmentType(kit.server(), CHAT_ABUSE);

            editor.resetToDefaults(kit.server());
            assertThat(catalog.snapshot().nameOf(CHAT_ABUSE)).isEqualTo("Chat Abuse");
            editor.deletePunishmentType(kit.server(), CHAT_ABUSE);

            assertThat(catalog.snapshot().nameOf(CHAT_ABUSE)).isEqualTo("Chat Abuse (deleted)");
            assertThat(catalog.snapshot().deletedByOrdinal()).containsOnlyKeys(CHAT_ABUSE);
            assertThat(storedList(kit, PunishmentTypeCatalog.DELETED_TYPES_SETTINGS_TYPE)).hasSize(1);
        }
    }

    @SqlContractTest
    void aRenamedTypeLeavesItsLastNameOnTheTombstone(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeCatalog catalog = kit.catalog();
            PunishmentTypeEditor editor = editor(kit, catalog);
            int ordinal = editor.createPunishmentType(kit.server(), custom("Griefing")).getOrdinal();

            editor.updatePunishmentType(kit.server(), ordinal, custom("Vandalism"));
            editor.deletePunishmentType(kit.server(), ordinal);

            assertThat(catalog.snapshot().nameOf(ordinal)).isEqualTo("Vandalism (deleted)");
        }
    }

    @SqlContractTest
    void deletingATypeLeavesItsPunishmentRowsUntouched(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            PunishmentTypeEditor editor = editor(kit, kit.catalog());
            int ordinal = editor.createPunishmentType(kit.server(), custom("Griefing")).getOrdinal();
            insertActiveBan(kit, "ban", ordinal, false);
            insertActiveBan(kit, "alt-ban", ordinal, true);
            Result<PunishmentsRecord> before = punishments(kit);

            editor.deletePunishmentType(kit.server(), ordinal);

            Result<PunishmentsRecord> after = punishments(kit);
            assertThat(after).isEqualTo(before);
            assertThat(after).extracting(PunishmentsRecord::getEnforcementCategory).containsExactly("BAN", "BAN");
        }
    }

    @SqlContractTest
    void aLostRaceIsRetriedAgainstTheNewList(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            editor(kit, kit.catalog()).seedDefaultTypes(kit.server());
            SettingsRepository repository = spy(kit.settingsRepository());
            doReturn(false).doCallRealMethod().when(repository)
                .updateIfVersion(eq(PunishmentTypeCatalog.TYPES_SETTINGS_TYPE), anyLong(), anyString());

            PunishmentType created = editor(kit, catalog(kit, repository)).createPunishmentType(kit.server(), custom("Griefing"));

            assertThat(created.getOrdinal()).isEqualTo(FIRST_CUSTOM_ORDINAL);
            assertThat(kit.catalog().snapshot().types()).extracting(PunishmentType::getName).containsOnlyOnce("Griefing");
        }
    }

    @SqlContractTest
    void aFailedTombstoneWriteRollsBackTheRemoval(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            editor(kit, kit.catalog()).seedDefaultTypes(kit.server());
            SettingsRepository repository = spy(kit.settingsRepository());
            doReturn(false).when(repository)
                .updateIfVersion(eq(PunishmentTypeCatalog.DELETED_TYPES_SETTINGS_TYPE), anyLong(), anyString());

            assertThatThrownBy(() -> editor(kit, catalog(kit, repository)).deletePunishmentType(kit.server(), CHAT_ABUSE))
                .isInstanceOf(ConcurrentUpdateException.class);

            assertThat(kit.catalog().snapshot().find(CHAT_ABUSE)).isPresent();
            assertThat(kit.catalog().snapshot().deletedByOrdinal()).isEmpty();
            assertThat(versionOfTypes(kit)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void appealFormOptionsMappingsAndHiddenSectionsArePersisted(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            AppealForm form = AppealForm.builder()
                .fields(List.of(AppealFormField.builder()
                    .id("kind")
                    .type("dropdown")
                    .options(List.of("Mistake", "Other.$"))
                    .optionSectionMapping(Map.of("Other.$", "details"))
                    .build()))
                .sections(List.of(AppealFormSection.builder().id("details").hideByDefault(true).build()))
                .build();

            PunishmentType created = editor(kit, kit.catalog())
                .createPunishmentType(kit.server(), custom("Griefing").toBuilder().appealForm(form).build());

            PunishmentType stored = kit.catalog().snapshot().find(created.getOrdinal()).orElseThrow();
            assertThat(stored.getAppealForm()).isEqualTo(form);
        }
    }

    @SqlContractTest
    void anUnreadableListIsShownAsDefaultsAndNeverOverwritten(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory, new RetryInjection())) {
            List<Map<String, Object>> unreadable = List.of(Map.of("ordinal", Map.of("unexpected", "shape"), "name", "Custom"));
            kit.store(PunishmentTypeCatalog.TYPES_SETTINGS_TYPE, unreadable);
            PunishmentTypeCatalog catalog = kit.catalog();
            PunishmentTypeEditor editor = editor(kit, catalog);

            editor.seedDefaultTypes(kit.server());

            assertThat(catalog.snapshot().types()).isEqualTo(DefaultPunishmentTypes.getAll());
            assertThatThrownBy(() -> editor.createPunishmentType(kit.server(), custom("Griefing"))).isInstanceOf(RuntimeException.class);
            assertThat(storedList(kit, PunishmentTypeCatalog.TYPES_SETTINGS_TYPE)).isEqualTo(unreadable);
            assertThat(versionOfTypes(kit)).isEqualTo(1);
        }
    }

    private static PunishmentType custom(String name) {
        return PunishmentType.builder().name(name).category("Gameplay").build();
    }

    private static PunishmentTypeEditor editor(InstanceTestKit kit, PunishmentTypeCatalog catalog) {
        return new PunishmentTypeEditor(catalog, kit.timestamps(), kit.unitOfWork());
    }

    private static PunishmentTypeCatalog catalog(InstanceTestKit kit, SettingsRepository repository) {
        return new PunishmentTypeCatalog(repository, kit.unitOfWork(), kit.database().jsonColumns(), kit.clock());
    }

    private static long versionOfTypes(InstanceTestKit kit) {
        return kit.settingsRepository().find(PunishmentTypeCatalog.TYPES_SETTINGS_TYPE).orElseThrow().version();
    }

    private static List<?> storedList(InstanceTestKit kit, String type) {
        return kit.database().jsonColumns().read(kit.settingsRepository().find(type).orElseThrow().dataJson(), List.class);
    }

    private static void insertActiveBan(InstanceTestKit kit, String id, int ordinal, boolean altBlocking) {
        PlayersRecord player = new PlayersRecord();
        player.setId("player-" + id);
        player.setMinecraftUuid(altBlocking ? "00000000-0000-4000-8000-000000000002" : "00000000-0000-4000-8000-000000000001");
        player.setUsername(id);
        player.setUsernameLower(id);
        player.setCreatedAt(kit.clock().instant());
        PunishmentsRecord punishment = new PunishmentsRecord();
        punishment.setId(id);
        punishment.setPlayerId(player.getId());
        punishment.setTypeOrdinal(ordinal);
        punishment.setIssuedAt(kit.clock().instant());
        punishment.setEnforcementCategory("BAN");
        punishment.setAltBlocking(altBlocking);
        punishment.setActiveUntilAt(kit.clock().instant().plusSeconds(86_400));
        kit.write(() -> {
            kit.database().dsl().insertInto(PLAYERS).set(player).execute();
            return kit.database().dsl().insertInto(PUNISHMENTS).set(punishment).execute();
        });
    }

    private static Result<PunishmentsRecord> punishments(InstanceTestKit kit) {
        return kit.database().dsl().selectFrom(PUNISHMENTS).orderBy(PUNISHMENTS.ID).fetch();
    }
}
