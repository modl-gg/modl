package gg.modl.backend.embedded;

import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.support.StandardServletEnvironment;

final class IsolatedEnvironment implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    static final String CONFIG_LOCATION_PROPERTY = "spring.config.location";
    static final String BUNDLED_CONFIG_LOCATION = "classpath:/application.properties";

    private static final List<String> HOST_PROPERTY_SOURCES = List.of(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
        StandardServletEnvironment.JNDI_PROPERTY_SOURCE_NAME
    );

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        detachFromHost(event.getEnvironment());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static void detachFromHost(ConfigurableEnvironment environment) {
        HOST_PROPERTY_SOURCES.forEach(environment.getPropertySources()::remove);
    }
}
