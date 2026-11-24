package gg.modl.backend.instance.repository;

import static gg.modl.backend.database.jooq.Tables.SETUP_STEPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.database.jooq.Tables;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.instance.ParallelCalls.Outcome;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class InstanceRepositoryContractTest {
    private static final Instant START = SqlTestDatabase.START;
    private static final Instant LATER = START.plusMillis(1234);
    private static final Instant LATEST = START.plusSeconds(3600);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void findIsEmptyUntilTheInstanceIsEnsured(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqInstanceRepository repository =
                new JooqInstanceRepository(database.dsl(), database.insertIfAbsent(), database.upserts());

            assertThat(repository.find()).isEmpty();
        }
    }

    @SqlContractTest
    void ensureCreatesOneUnclaimedRowAndLaterKeepsTheStoredName(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            Server again = kit.write(() -> kit.instanceRepository().ensure("Another name", LATER));

            assertThat(again.getServerName()).isEqualTo(InstanceTestKit.SERVER_NAME);
            assertThat(again.getId()).isEqualTo("instance");
            assertThat(again.isClaimed()).isFalse();
            assertThat(again.getCreatedAt()).isEqualTo(START);
            assertThat(again.getUpdatedAt()).isEqualTo(START);
            assertThat(again.getSetup().startedAt()).isEqualTo(START);
            assertThat(again.getSetup().steps()).isEmpty();
            assertThat(kit.database().dsl().fetchCount(Tables.INSTANCE)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aClaimStoresOwnerAndAddressAndKeepsTheSetupStart(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            assertThat(kit.write(() -> kit.instanceRepository().claim("owner@example.com", "https://panel.example.com", LATER))).isTrue();
            assertThat(kit.write(() -> kit.instanceRepository().claim("other@example.com", "https://evil.example.com", LATEST))).isFalse();

            Server stored = stored(kit);
            assertThat(stored.getAdminEmail()).isEqualTo("owner@example.com");
            assertThat(stored.getPublicUrl()).isEqualTo("https://panel.example.com");
            assertThat(stored.getUpdatedAt()).isEqualTo(LATER);
            assertThat(stored.getSetup().startedAt()).isEqualTo(START);
        }
    }

    @SqlContractTest
    void anEmptyOwnerCanBeClaimedAndAMissingSetupStartIsFilledIn(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.database().dsl().update(Tables.INSTANCE)
                .set(Tables.INSTANCE.ADMIN_EMAIL, "")
                .setNull(Tables.INSTANCE.SETUP_STARTED_AT)
                .execute());

            assertThat(kit.write(() -> kit.instanceRepository().claim("owner@example.com", null, LATER))).isTrue();

            assertThat(stored(kit).getPublicUrl()).isNull();
            assertThat(stored(kit).getSetup().startedAt()).isEqualTo(LATER);
        }
    }

    @SqlContractTest
    void ofEightConcurrentClaimsExactlyOneWins(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            List<Outcome<Boolean>> outcomes = ParallelCalls.run(8, caller ->
                kit.write(() -> kit.instanceRepository().claim("owner" + caller + "@example.com", null, LATER)));

            assertThat(outcomes).allMatch(Outcome::succeeded);
            List<Integer> winners = IntStream.range(0, 8).filter(caller -> outcomes.get(caller).value()).boxed().toList();
            assertThat(winners).hasSize(1);
            assertThat(stored(kit).getAdminEmail()).isEqualTo("owner" + winners.getFirst() + "@example.com");
        }
    }

    @SqlContractTest
    void theFirstOwnerSignInMarksTheAccountStepAndOnlyCountsOnce(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            assertThat(kit.write(() -> kit.instanceRepository().recordOwnerSignIn(LATER))).isTrue();
            assertThat(kit.write(() -> kit.instanceRepository().recordOwnerSignIn(LATEST))).isFalse();

            Server stored = stored(kit);
            assertThat(stored.getSetup().ownerSignedInAt()).isEqualTo(LATER);
            assertThat(stored.getSetup().statusOf(SetupStep.ACCOUNT)).isEqualTo(SetupStepStatus.DONE);
        }
    }

    @SqlContractTest
    void aConfiguredOwnerHasToSignInAgainWhileAnEditedEmailKeepsTheSignIn(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.instanceRepository().recordOwnerSignIn(LATER));

            write(kit, repository -> repository.updateAdminEmail("edited@example.com", LATEST));
            assertThat(stored(kit).getAdminEmail()).isEqualTo("edited@example.com");
            assertThat(stored(kit).getSetup().ownerSignedInAt()).isEqualTo(LATER);

            write(kit, repository -> repository.assignConfiguredAdminEmail("configured@example.com", LATEST));
            assertThat(stored(kit).getAdminEmail()).isEqualTo("configured@example.com");
            assertThat(stored(kit).getSetup().ownerSignedInAt()).isNull();
        }
    }

    @SqlContractTest
    void everyStepAndStatusRoundTripsAndTheLastWriteWins(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            for (SetupStep step : SetupStep.values()) {
                for (SetupStepStatus status : SetupStepStatus.values()) {
                    write(kit, repository -> repository.updateSetupStep(step, status, LATER));

                    assertThat(stored(kit).getSetup().steps()).containsEntry(step.name(), status.name());
                }
            }
            assertThat(kit.database().dsl().fetchCount(SETUP_STEPS)).isEqualTo(SetupStep.values().length);
            assertThat(stored(kit).getUpdatedAt()).isEqualTo(LATER);
        }
    }

    @SqlContractTest
    void completingSetupKeepsTheFirstCompletionTime(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            write(kit, repository -> repository.completeSetup(LATER));
            write(kit, repository -> repository.completeSetup(LATEST));

            assertThat(stored(kit).getSetup().completedAt()).isEqualTo(LATER);
        }
    }

    @SqlContractTest
    void anInstanceThatDoesNotTrackSetupIgnoresWizardWrites(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            kit.write(() -> kit.database().dsl().update(Tables.INSTANCE).setNull(Tables.INSTANCE.SETUP_STARTED_AT).execute());

            assertThat(kit.write(() -> kit.instanceRepository().recordOwnerSignIn(LATER))).isFalse();
            write(kit, repository -> repository.updateSetupStep(SetupStep.EMAIL, SetupStepStatus.DONE, LATER));
            write(kit, repository -> repository.completeSetup(LATER));

            assertThat(stored(kit).getSetup()).isNull();
            assertThat(kit.database().dsl().fetchCount(SETUP_STEPS)).isZero();
            assertThat(stored(kit).getUpdatedAt()).isEqualTo(START);
        }
    }

    @SqlContractTest
    void identityFieldsAreUpdatedOneByOne(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            write(kit, repository -> repository.updateServerName("Blöck Party 🎮", LATER));
            write(kit, repository -> repository.updateApiKey("modl_key", LATER));
            write(kit, repository -> repository.updatePublicUrl("https://panel.example.com", LATEST));

            Server stored = stored(kit);
            assertThat(stored.getServerName()).isEqualTo("Blöck Party 🎮");
            assertThat(stored.getApiKey()).isEqualTo("modl_key");
            assertThat(stored.getPublicUrl()).isEqualTo("https://panel.example.com");
            assertThat(stored.getUpdatedAt()).isEqualTo(LATEST);

            write(kit, repository -> repository.updatePublicUrl(null, LATEST));
            assertThat(stored(kit).getPublicUrl()).isNull();
        }
    }

    @SqlContractTest
    void anObservedAddressIsStoredOnlyWhileNoneIsKnown(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            write(kit, repository -> repository.updatePublicUrl("", LATER));

            assertThat(kit.write(() -> kit.instanceRepository().storePublicUrlIfUnset("https://first.example.com", LATER))).isTrue();
            assertThat(kit.write(() -> kit.instanceRepository().storePublicUrlIfUnset("https://second.example.com", LATEST))).isFalse();
            assertThat(stored(kit).getPublicUrl()).isEqualTo("https://first.example.com");
        }
    }

    @SqlContractTest
    void theTwoInvalidationTimestampsAreIndependent(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            write(kit, repository -> repository.updateStaffPermissionsTimestamp(LATER));
            write(kit, repository -> repository.updatePunishmentTypesTimestamp(LATEST));

            assertThat(stored(kit).getStaffPermissionsUpdatedAt()).isEqualTo(LATER);
            assertThat(stored(kit).getPunishmentTypesUpdatedAt()).isEqualTo(LATEST);
            assertThat(stored(kit).getUpdatedAt()).isEqualTo(LATEST);
        }
    }

    @SqlContractTest
    void columnLimitsAreCountedInCodePoints(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            String widest = "🎮".repeat(100);
            write(kit, repository -> repository.updateServerName(widest, LATER));

            assertThat(stored(kit).getServerName()).isEqualTo(widest);
            assertThatThrownBy(() -> write(kit, repository -> repository.updateServerName(widest + "x", LATER)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> write(kit, repository -> repository.updateAdminEmail("a".repeat(255), LATER)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> write(kit, repository -> repository.updateApiKey("k".repeat(256), LATER)))
                .isInstanceOf(ValueTooLongException.class);
            assertThatThrownBy(() -> write(kit, repository -> repository.updatePublicUrl("u".repeat(2049), LATER)))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    private static void write(InstanceTestKit kit, Consumer<InstanceRepository> change) {
        kit.unitOfWork().write(() -> change.accept(kit.instanceRepository()));
    }

    private static Server stored(InstanceTestKit kit) {
        return kit.instanceRepository().find().orElseThrow();
    }
}
