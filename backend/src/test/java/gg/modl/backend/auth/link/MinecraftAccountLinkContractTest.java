package gg.modl.backend.auth.link;

import static gg.modl.backend.database.jooq.Tables.MINECRAFT_LINK_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.service.MinecraftLinkOutcome;
import gg.modl.backend.staff.service.MinecraftStaffService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class MinecraftAccountLinkContractTest {
    private static final String EMAIL = "mod@example.com";
    private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private final Server server = TestServers.server("Server", "owner@example.com");
    private final MinecraftStaffService staffService = mock(MinecraftStaffService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aRetriedRedeemStillLinksAndConsumesTheCode(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            MinecraftAccountLinkService service = service(database);
            LinkCodeIssue issue = service.issueCode(server, EMAIL);
            when(staffService.linkOwnMinecraftAccount(server, EMAIL, UUID, "Steve")).thenReturn(MinecraftLinkOutcome.LINKED);

            assertThat(service.redeem(server, issue.code(), UUID, "Steve")).isEqualTo(AccountLinkResult.LINKED);
            assertThat(database.dsl().fetchCount(MINECRAFT_LINK_CODES)).isZero();
        }
    }

    @SqlContractTest
    void aFailedLinkRollsTheConsumeBack(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            MinecraftAccountLinkService service = service(database);
            LinkCodeIssue issue = service.issueCode(server, EMAIL);
            when(staffService.linkOwnMinecraftAccount(server, EMAIL, UUID, "Steve"))
                .thenThrow(new IllegalStateException("staff update failed"));

            assertThatThrownBy(() -> service.redeem(server, issue.code(), UUID, "Steve")).hasMessageContaining("staff update failed");
            assertThat(database.dsl().fetchCount(MINECRAFT_LINK_CODES)).isEqualTo(1);
        }
    }

    private MinecraftAccountLinkService service(SqlTestDatabase database) {
        return new MinecraftAccountLinkService(AuthRepositories.on(database).linkCodes(), staffService,
            new OneTimeCodeCodec(() -> "secret"), database.unitOfWork(), database.clock());
    }
}
