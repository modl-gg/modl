package gg.modl.backend.instance.setup;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.setup")
public record SetupProperties(@Nullable String codeFile) {

    public SetupProperties {
        codeFile = codeFile == null || codeFile.isBlank() ? null : codeFile.trim();
    }

    public Optional<String> configuredCodeFile() {
        return Optional.ofNullable(codeFile);
    }
}
