package gg.modl.backend.settings.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.instance.ParallelCalls.Outcome;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class SettingsRepositoryContractTest {
    private static final String TYPE = "general";
    private static final String JSON = "{\"a.b\":{\"$c\":[1,null,true]},\"name\":\"Grüße 🎮\"}";
    private static final Instant START = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theFirstStoredVersionIsOneSoItDiffersFromAnAbsentRow(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            assertThat(kit.settingsRepository().find(TYPE)).isEmpty();

            assertThat(kit.write(() -> kit.settingsRepository().insertIfAbsent(TYPE, JSON))).isTrue();

            assertThat(kit.settingsRepository().find(TYPE)).contains(new SettingsRow(TYPE, JSON, 1, START));
        }
    }

    @SqlContractTest
    void anInsertNeverOverwritesAStoredRow(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.settingsRepository().insertIfAbsent(TYPE, JSON));
            kit.clock().advance(Duration.ofSeconds(1));

            assertThat(kit.write(() -> kit.settingsRepository().insertIfAbsent(TYPE, "{}"))).isFalse();

            assertThat(kit.settingsRepository().find(TYPE)).contains(new SettingsRow(TYPE, JSON, 1, START));
        }
    }

    @SqlContractTest
    void anUpdateNeedsTheCurrentVersion(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            assertThat(update(kit, 0, "{}")).isFalse();
            kit.write(() -> kit.settingsRepository().insertIfAbsent(TYPE, JSON));
            kit.clock().advance(Duration.ofMillis(1500));

            assertThat(update(kit, 1, "{\"n\":2}")).isTrue();
            assertThat(update(kit, 1, "{\"n\":3}")).isFalse();

            assertThat(kit.settingsRepository().find(TYPE)).contains(new SettingsRow(TYPE, "{\"n\":2}", 2, START.plusMillis(1500)));
        }
    }

    @SqlContractTest
    void ofEightWritersWithTheSameVersionExactlyOneWins(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.settingsRepository().insertIfAbsent(TYPE, JSON));

            List<Outcome<Boolean>> outcomes = ParallelCalls.run(8, caller -> update(kit, 1, "{\"writer\":" + caller + "}"));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            assertThat(outcomes).extracting(Outcome::value).containsOnlyOnce(true);
            assertThat(kit.settingsRepository().find(TYPE)).map(SettingsRow::version).contains(2L);
        }
    }

    @SqlContractTest
    void anUpsertInsertsThenBumpsTheVersionAndTime(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.unitOfWork().write(() -> kit.settingsRepository().upsert(TYPE, JSON));
            assertThat(kit.settingsRepository().find(TYPE)).contains(new SettingsRow(TYPE, JSON, 1, START));
            kit.clock().advance(Duration.ofSeconds(2));

            kit.unitOfWork().write(() -> kit.settingsRepository().upsert(TYPE, "[]"));

            assertThat(kit.settingsRepository().find(TYPE)).contains(new SettingsRow(TYPE, "[]", 2, START.plusSeconds(2)));
        }
    }

    @SqlContractTest
    void deleteRemovesOnlyThatTypeAndReportsWhetherItExisted(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.settingsRepository().insertIfAbsent(TYPE, JSON));
            kit.write(() -> kit.settingsRepository().insertIfAbsent("mail", "{}"));

            assertThat(kit.write(() -> kit.settingsRepository().delete(TYPE))).isTrue();
            assertThat(kit.write(() -> kit.settingsRepository().delete(TYPE))).isFalse();

            assertThat(kit.settingsRepository().find(TYPE)).isEmpty();
            assertThat(kit.settingsRepository().find("mail")).isPresent();
        }
    }

    @SqlContractTest
    void typesIgnoreTrailingSpacesButNotCase(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.settingsRepository().insertIfAbsent("ticketForms  ", "{}"));

            assertThat(kit.settingsRepository().find("ticketForms")).map(SettingsRow::type).contains("ticketForms");
            assertThat(kit.settingsRepository().find("ticketForms ")).isPresent();
            assertThat(kit.settingsRepository().find("ticketforms")).isEmpty();
        }
    }

    @SqlContractTest
    void theTypeIsLimitedInCodePointsAndTheDocumentIsNot(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            String widest = "🎮".repeat(64);
            String large = "\"" + "x".repeat(60_000) + "\"";

            assertThat(kit.write(() -> kit.settingsRepository().insertIfAbsent(widest, large))).isTrue();

            assertThat(kit.settingsRepository().find(widest)).map(SettingsRow::dataJson).contains(large);
            assertThatThrownBy(() -> kit.write(() -> kit.settingsRepository().insertIfAbsent(widest + "x", "{}")))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    private static boolean update(InstanceTestKit kit, long version, String json) {
        return kit.write(() -> kit.settingsRepository().updateIfVersion(TYPE, version, json));
    }
}
