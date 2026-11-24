package gg.modl.backend.serverkey.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.serverkey.ServerKey;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.io.TempDir;

class JooqServerKeyRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aKeyIsFoundByHashAndIdAndListedInCreationOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqServerKeyRepository repository = repository(database);
            ServerKey lobby = key("k2", "Lobby", "a", NOW.plusSeconds(1));
            ServerKey proxy = key("k1", "Proxy", "b", NOW);

            assertThat(database.unitOfWork().write(() -> repository.insertIfAbsent(lobby))).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.insertIfAbsent(proxy))).isTrue();

            assertThat(repository.findByKeyHash(lobby.keyHash())).contains(lobby);
            assertThat(repository.findById("k1")).contains(proxy);
            assertThat(repository.findAll()).extracting(ServerKey::id).containsExactly("k1", "k2");
            assertThat(repository.count()).isEqualTo(2);
        }
    }

    @SqlContractTest
    void namesAreUniqueWithoutRegardToCase(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqServerKeyRepository repository = repository(database);
            database.unitOfWork().write(() -> repository.insertIfAbsent(key("k1", "Lobby", "a", NOW)));

            assertThat(repository.existsByName("LOBBY")).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.insertIfAbsent(key("k2", "lobby", "b", NOW)))).isFalse();
            assertThat(database.unitOfWork().write(() -> repository.insertIfAbsent(key("k3", "Other", "a", NOW)))).isFalse();
            assertThat(repository.count()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void theProxyCommandSettingAndDeletionAffectOneKey(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqServerKeyRepository repository = repository(database);
            database.unitOfWork().write(() -> repository.insertIfAbsent(key("k1", "Lobby", "a", NOW)));
            database.unitOfWork().write(() -> repository.insertIfAbsent(key("k2", "Survival", "b", NOW)));

            assertThat(database.unitOfWork().write(() -> repository.setAllowProxyCommands("k1", true))).isTrue();
            assertThat(repository.findById("k1").orElseThrow().allowProxyCommands()).isTrue();
            assertThat(repository.findById("k2").orElseThrow().allowProxyCommands()).isFalse();

            assertThat(database.unitOfWork().write(() -> repository.delete("k1"))).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.delete("k1"))).isFalse();
            assertThat(repository.findByKeyHash(hash("a"))).isEmpty();
            assertThat(repository.findAll()).extracting(ServerKey::id).containsExactly("k2");
        }
    }

    @SqlContractTest
    void lastSeenIsWrittenOnlyWhenItIsOlderThanTheThrottle(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqServerKeyRepository repository = repository(database);
            database.unitOfWork().write(() -> repository.insertIfAbsent(key("k1", "Lobby", "a", NOW)));
            Instant first = NOW.plusSeconds(10);
            Instant second = first.plusSeconds(60);

            assertThat(database.unitOfWork().write(() -> repository.recordSeen("k1", first, first))).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.recordSeen("k1", second, second.minus(Duration.ofMinutes(5)))))
                .isFalse();
            assertThat(repository.findById("k1").orElseThrow().lastSeenAt()).isEqualTo(first);
        }
    }

    @SqlContractTest
    void overLongValuesAreRefusedAndLookupsOverTheLimitMatchNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqServerKeyRepository repository = repository(database);

            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.insertIfAbsent(key("k1", "x".repeat(65), "a", NOW))))
                .isInstanceOf(ValueTooLongException.class);
            assertThat(repository.findByKeyHash("f".repeat(65))).isEmpty();
            assertThat(repository.existsByName("x".repeat(65))).isFalse();
        }
    }

    private static JooqServerKeyRepository repository(SqlTestDatabase database) {
        return new JooqServerKeyRepository(database.dsl(), database.insertIfAbsent());
    }

    private static ServerKey key(String id, String name, String hashSeed, Instant createdAt) {
        return new ServerKey(id, name, ServerKeyScope.BEHIND_PROXY, false, hash(hashSeed), "abcdefgh", "owner@example.com",
            createdAt, null);
    }

    private static String hash(String seed) {
        return seed.repeat(64).substring(0, 64);
    }
}
