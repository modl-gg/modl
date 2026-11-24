package gg.modl.backend.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoVendorSdkTest {
    private static final List<String> FORBIDDEN = List.of("com.google.genai", "LLMService", "AiApiKey", "settings/ai-key");

    @Test
    void theBackendTalksToProvidersOnlyThroughItsOwnClients() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src/main"))) {
            List<String> offenders = sources.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".properties"))
                .filter(NoVendorSdkTest::mentionsForbiddenName)
                .map(Path::toString)
                .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static boolean mentionsForbiddenName(Path path) {
        try {
            String content = Files.readString(path);
            return FORBIDDEN.stream().anyMatch(content::contains);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
