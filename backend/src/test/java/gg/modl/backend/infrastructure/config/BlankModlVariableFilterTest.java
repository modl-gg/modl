package gg.modl.backend.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class BlankModlVariableFilterTest {

    @Test
    void blankModlVariablesBehaveAsUnsetSoPlaceholderDefaultsApply() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> variables = new HashMap<>();
        variables.put("MODL_SMTP_PORT", "");
        variables.put("MODL_SERVER_NAME", "  ");
        variables.put("MODL_PORT", "9090");
        variables.put("OTHER_VARIABLE", "");
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.getPropertySources().addFirst(new MapPropertySource("dotenv", Map.of("MODL_API_KEY", "")));

        new BlankModlVariableFilter().postProcessEnvironment(environment, null);

        assertEquals("587", environment.resolvePlaceholders("${MODL_SMTP_PORT:587}"));
        assertEquals("Minecraft Server", environment.resolvePlaceholders("${MODL_SERVER_NAME:Minecraft Server}"));
        assertEquals("9090", environment.resolvePlaceholders("${MODL_PORT:8080}"));
        assertEquals("", environment.getProperty("OTHER_VARIABLE"));
        assertNull(environment.getProperty("MODL_API_KEY"));
    }
}
