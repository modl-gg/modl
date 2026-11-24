package gg.modl.backend.embedded;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class IsolatedEnvironmentTest {

    @Test
    void hostEnvironmentVariablesAndSystemPropertiesNeverReachTheEmbeddedServer() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("SERVER_PORT", "25565", "DEBUG", "true", "SPRING_PROFILES_ACTIVE", "host")));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of("server.address", "10.0.0.1")));
        environment.getPropertySources().addFirst(new MapPropertySource("modlOverrides", Map.of("server.port", "18190")));

        IsolatedEnvironment.detachFromHost(environment);

        assertThat(environment.getProperty("server.port")).isEqualTo("18190");
        assertThat(environment.containsProperty("debug")).isFalse();
        assertThat(environment.containsProperty("server.address")).isFalse();
        assertThat(environment.containsProperty("spring.profiles.active")).isFalse();
    }
}
