package gg.modl.backend.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DockerComposeDefaultsTest {
    private static final Path REPOSITORY_ROOT = Path.of("..");

    @Test
    void hostDockerInternalResolvesOnLinux() throws IOException {
        assertTrue(modlService().get("extra_hosts") instanceof List<?> hosts
            && hosts.contains("host.docker.internal:host-gateway"));
    }

    @Test
    void timeZoneIsPassedThroughWithUtcAsDefault() throws IOException {
        Map<?, ?> environment = (Map<?, ?>) modlService().get("environment");
        assertEquals("${TZ:-UTC}", environment.get("TZ"));
    }

    @Test
    void memoryLimitIsOptional() throws IOException {
        assertEquals("${MODL_MEMORY_LIMIT:-0}", modlService().get("mem_limit"));
    }

    @Test
    void envExampleDocumentsTheDockerDefaults() throws IOException {
        String example = Files.readString(REPOSITORY_ROOT.resolve(".env.example"));
        assertTrue(example.contains("# TZ=UTC"), example);
        assertTrue(example.contains("# MODL_MEMORY_LIMIT="), example);
    }

    @Test
    void imageCarriesTimeZoneData() throws IOException {
        assertTrue(Files.readString(REPOSITORY_ROOT.resolve("Dockerfile")).contains(" tzdata "));
    }

    private static Map<?, ?> modlService() throws IOException {
        try (Reader reader = Files.newBufferedReader(REPOSITORY_ROOT.resolve("docker-compose.yml"))) {
            Map<?, ?> compose = new Yaml().load(reader);
            return (Map<?, ?>) ((Map<?, ?>) compose.get("services")).get("modl");
        }
    }
}
