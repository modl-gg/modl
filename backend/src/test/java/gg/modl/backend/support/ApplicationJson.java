package gg.modl.backend.support;

import gg.modl.backend.infrastructure.json.InstantJsonConfiguration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

public final class ApplicationJson {

    public static final JsonMapper MAPPER = applicationMapper();

    private ApplicationJson() {
    }

    private static JsonMapper applicationMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(InstantJsonConfiguration.class)
            .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return mapper.get();
    }
}
