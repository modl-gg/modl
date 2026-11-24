package gg.modl.backend.infrastructure.json;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InstantJsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer instantJsonFormat() {
        return builder -> builder.addModule(InstantJsonFormat.module());
    }
}
