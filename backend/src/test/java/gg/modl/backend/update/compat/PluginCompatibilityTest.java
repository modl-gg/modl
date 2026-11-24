package gg.modl.backend.update.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.update.ReleaseVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class PluginCompatibilityTest {
    private static final String BOOT_ID = "11111111-2222-3333-4444-555555555555";

    private final RefusedServers refused = new RefusedServers(Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"), ZoneOffset.UTC));
    private final PluginCompatibilityPolicy policy = new PluginCompatibilityPolicy(
        ReleaseBuild.of(ReleaseVersion.parse("3.1.2"), ReleaseVersion.parse("3.0.0")), refused, mock(OperatorNotice.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe(), new MinecraftCompatibilityController(policy))
        .setControllerAdvice(new CompatibilityRefusalAdvice())
        .addInterceptors(new PluginCompatibilityInterceptor(policy))
        .build();

    @Test
    void aRequestWithoutThePluginHeaderIsAllowed() throws Exception {
        mvc.perform(get("/v3/minecraft/probe")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"3.1.0", "3.1.9", "3.1.2-rc.1"})
    void theSameLineIsAllowedAndLearnsTheServerVersion(String version) throws Exception {
        mvc.perform(fromPlugin("/v3/minecraft/probe", version))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Modl-Server-Version", "3.1.2"));
    }

    @ParameterizedTest
    @CsvSource({"3.0.9, PLUGIN_OLDER", "2.9.0, PLUGIN_OLDER", "3.2.0, SERVER_OLDER", "dev, UNKNOWN", "'', UNKNOWN"})
    void anotherLineIsRefusedWithTheFrozenContract(String version, String compatibility) throws Exception {
        mvc.perform(fromPlugin("/v3/minecraft/probe", version))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Modl-Compatibility", compatibility))
            .andExpect(header().string("X-Modl-Server-Version", "3.1.2"))
            .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));
    }

    @Test
    void theRefusalIsJsonEvenForProtobufClients() throws Exception {
        mvc.perform(fromPlugin("/v3/minecraft/probe", "3.0.0").accept("application/x-protobuf"))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Modl-Compatibility", "PLUGIN_OLDER"));
    }

    @Test
    void theProbeAnswersNoContentOrTheRefusal() throws Exception {
        mvc.perform(fromPlugin("/v2/minecraft/compatibility", "3.1.0")).andExpect(status().isNoContent());
        mvc.perform(fromPlugin("/v2/minecraft/compatibility", "3.0.0")).andExpect(status().isConflict());
    }

    @Test
    void onlyMinecraftRoutesAreChecked() throws Exception {
        mvc.perform(fromPlugin("/v1/panel/probe", "2.9.0")).andExpect(status().isOk());
        mvc.perform(fromPlugin("/v1/minecraft/probe", "2.9.0")).andExpect(status().isConflict());
    }

    @Test
    void aRefusedProcessIsListedUntilItIsAccepted() throws Exception {
        mvc.perform(fromPlugin("/v3/minecraft/probe", "3.0.0"));
        assertThat(refused.current()).singleElement().satisfies(server -> {
            assertThat(server.bootId()).isEqualTo(BOOT_ID);
            assertThat(server.pluginVersion()).isEqualTo("3.0.0");
        });

        mvc.perform(fromPlugin("/v3/minecraft/probe", "3.1.0"));
        assertThat(refused.current()).isEmpty();
    }

    @Test
    void versionsShownOnTheConsoleCannotCarryControlCharacters() {
        assertThat(PluginIdentity.of("3.0\n\u001b[31m", null, "SPIGOT").pluginVersion()).isEqualTo("3.0???31m");
    }

    @Test
    void refusalsStayBounded() {
        for (int index = 0; index < RefusedServers.CAPACITY + 10; index++) {
            refused.refused(PluginIdentity.of("3.0.0", String.format("%08d", index), "SPIGOT"),
                policy.compatibilityOf("3.0.0"));
        }

        assertThat(refused.current()).hasSize(RefusedServers.CAPACITY);
    }

    private static MockHttpServletRequestBuilder fromPlugin(String path, String version) {
        return get(path)
            .header("X-Modl-Plugin-Version", version)
            .header("X-Modl-Boot-Id", BOOT_ID)
            .header("X-Modl-Platform", "SPIGOT");
    }

    @RestController
    static final class Probe {
        @GetMapping("/v3/minecraft/probe")
        String probe() {
            return "ok";
        }

        @GetMapping("/v1/minecraft/probe")
        String versionOneProbe() {
            return "ok";
        }

        @GetMapping("/v1/panel/probe")
        String panelProbe() {
            return "ok";
        }
    }
}
