package gg.modl.backend.infrastructure.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

public class BlankModlVariableFilter implements EnvironmentPostProcessor, Ordered {
    private static final String VARIABLE_PREFIX = "MODL_";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        List<PropertySource<?>> snapshot = sources.stream().toList();
        for (PropertySource<?> source : snapshot) {
            if (source instanceof MapPropertySource mapSource && containsBlankModlVariable(mapSource.getSource())) {
                sources.replace(source.getName(), withoutBlankModlVariables(mapSource));
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    private static boolean containsBlankModlVariable(Map<String, Object> values) {
        return values.entrySet().stream().anyMatch(entry -> isBlankModlVariable(entry.getKey(), entry.getValue()));
    }

    private static MapPropertySource withoutBlankModlVariables(MapPropertySource source) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        source.getSource().forEach((key, value) -> {
            if (!isBlankModlVariable(key, value)) {
                filtered.put(key, value);
            }
        });
        if (isSystemEnvironment(source)) {
            return new SystemEnvironmentPropertySource(source.getName(), filtered);
        }
        return new MapPropertySource(source.getName(), filtered);
    }

    private static boolean isSystemEnvironment(PropertySource<?> source) {
        return source instanceof SystemEnvironmentPropertySource
            || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(source.getName());
    }

    private static boolean isBlankModlVariable(String key, Object value) {
        return key.startsWith(VARIABLE_PREFIX) && value instanceof CharSequence text && text.toString().isBlank();
    }
}
