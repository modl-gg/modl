package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.instance.ParallelCalls;
import gg.modl.backend.instance.ParallelCalls.Outcome;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SetupClaimContractTest {
    private static final String PANEL = "https://panel.example.com";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void ofEightClaimsWithTheSameCodeExactlyOneOwnsTheInstance(DialectTarget target) throws Exception {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            Wizard wizard = Wizard.of(kit);
            String code = wizard.issue();

            List<Outcome<String>> outcomes = ParallelCalls.run(8, caller ->
                wizard.claims().claim(code, "owner" + caller + "@example.com", PANEL, SetupClient.identifiedBy("198.51.100." + caller)));

            List<String> owners = outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
            assertThat(owners).hasSize(1);
            Server stored = kit.instanceRepository().find().orElseThrow();
            assertThat(stored.getAdminEmail()).isEqualTo(owners.getFirst());
            assertThat(stored.getPublicUrl()).isEqualTo(PANEL);
            assertThat(kit.server().getAdminEmail()).isEqualTo(owners.getFirst());
            assertThat(kit.secretsRepository().findSetupCode().orElseThrow().getSetupCodeHash()).isNull();
            verify(wizard.file(), times(1)).delete();
        }
    }

    @SqlContractTest
    void losingTheClaimRollsBackTheConsumedCode(DialectTarget target) {
        try (InstanceTestKit kit = InstanceTestKit.start(target, dataDirectory)) {
            Wizard wizard = Wizard.of(kit);
            String code = wizard.issue();
            kit.write(() -> kit.instanceRepository().claim("first@example.com", PANEL, kit.clock().instant()));

            assertThatThrownBy(() -> wizard.claims().claim(code, "second@example.com", PANEL, SetupClient.identifiedBy("198.51.100.2")))
                .isInstanceOf(ConflictException.class);

            assertThat(kit.instanceRepository().find().orElseThrow().getAdminEmail()).isEqualTo("first@example.com");
            assertThat(kit.secretsRepository().findSetupCode().orElseThrow().getSetupCodeHash()).isNotNull();
            verify(wizard.file(), never()).delete();
        }
    }

    private record Wizard(SetupCodeService codes, SetupClaimService claims, SetupCodeAnnouncer announcer, SetupCodeFile file) {
        static Wizard of(InstanceTestKit kit) {
            SetupCodeAnnouncer announcer = mock(SetupCodeAnnouncer.class);
            SetupCodeFile file = mock(SetupCodeFile.class);
            SetupCodeService codes = new SetupCodeService(kit.secretsRepository(), kit.unitOfWork(), kit.instanceContext(),
                new OneTimeCodeCodec(() -> "secret"), new SetupCodeAttempts(), announcer, file, kit.clock());
            SetupClaimService claims = new SetupClaimService(kit.instanceContext(), kit.instanceService(), codes);
            return new Wizard(codes, claims, announcer, file);
        }

        String issue() {
            codes.issueIfNeeded();
            ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
            verify(announcer).announce(any(), code.capture(), any());
            return code.getValue();
        }
    }
}
