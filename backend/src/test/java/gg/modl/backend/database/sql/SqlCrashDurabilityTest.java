package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.jooq.Tables;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PlayerRepositories;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlCrashDurabilityTest {
    private static final List<String> BANS = List.of(CrashWorkload.FIRST_BAN, "ban-2");
    private static final String CLAIMED_SERVER = "Crash Server";

    @TempDir
    Path directory;

    @Test
    void everyWriteSurvivesAKillZeroMillisecondsAfterItsAcknowledgement() throws Exception {
        CrashingEngineProcess.killRightAfterTheAcknowledgement(directory, CrashWorkload.CLAIM, CLAIMED_SERVER);
        for (String ban : BANS) {
            CrashingEngineProcess.killRightAfterTheAcknowledgement(directory, CrashWorkload.PUNISH, ban);
        }

        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, directory)) {
            PlayerRepositories repositories = PlayerRepositories.on(database);
            assertThat(database.dsl().select(Tables.INSTANCE.SERVER_NAME).from(Tables.INSTANCE).fetch(Tables.INSTANCE.SERVER_NAME))
                .containsExactly(CLAIMED_SERVER);
            assertThat(database.dsl().select(Tables.INSTANCE.ADMIN_EMAIL).from(Tables.INSTANCE).fetch(Tables.INSTANCE.ADMIN_EMAIL))
                .containsExactly(CrashWorkload.OWNER_EMAIL);
            List<String> players = database.dsl().select(PLAYERS.ID).from(PLAYERS).fetch(PLAYERS.ID);
            assertThat(players).hasSize(1);
            assertThat(repositories.punishments().findByPlayer(players.getFirst())).extracting(Punishment::getId)
                .containsExactlyElementsOf(BANS);
            for (String ban : BANS) {
                PunishmentRecord stored = repositories.punishments().findById(ban).orElseThrow();
                assertThat(stored.playerMinecraftUuid()).isEqualTo(CrashWorkload.VICTIM_UUID);
                assertThat(stored.punishment().getNotes()).hasSize(CrashWorkload.NOTES);
                assertThat(stored.punishment().getEvidence()).hasSize(CrashWorkload.EVIDENCE);
                assertThat(stored.punishment().data().reason()).isEqualTo("crash test");
            }
            assertThat(database.dsl().select(AUDIT_LOGS.DESCRIPTION).from(AUDIT_LOGS).orderBy(AUDIT_LOGS.ID).fetch(AUDIT_LOGS.DESCRIPTION))
                .containsExactlyElementsOf(BANS);
        }
        CrashedDatabase.assertSound(directory);
    }
}
