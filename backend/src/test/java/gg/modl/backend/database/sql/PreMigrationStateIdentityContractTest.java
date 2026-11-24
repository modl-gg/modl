package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class PreMigrationStateIdentityContractTest {
    @TempDir
    Path directory;

    @SqlContractTest
    void theDatabaseIdentityFollowsTheInstanceAndNotTheDatabaseName(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            instanceKey(database, "first-instance-signing-key");
            String first = identity(database);
            assertThat(identity(database)).isEqualTo(first);

            instanceKey(database, "another-instance-signing-key");
            String other = identity(database);

            assertThat(other).isNotEqualTo(first).doesNotContain("signing-key");
        }
    }

    private String identity(SqlTestDatabase database) {
        return PreMigrationState.read(database.bean(ConnectionPools.class).writer(), database.dialect(),
            new DataDirectoryProperties(directory.toString())).databaseId();
    }

    private static void instanceKey(SqlTestDatabase database, String key) {
        database.unitOfWork().write(() -> {
            database.dsl().deleteFrom(INSTANCE_SECRETS).execute();
            database.dsl().insertInto(INSTANCE_SECRETS)
                .set(INSTANCE_SECRETS.ID, 1)
                .set(INSTANCE_SECRETS.AUTH_CODE_HASH_SECRET, "auth-secret")
                .set(INSTANCE_SECRETS.STORAGE_SIGNING_KEY, key)
                .set(INSTANCE_SECRETS.CREATED_AT, SqlTestDatabase.START)
                .execute();
        });
    }
}
