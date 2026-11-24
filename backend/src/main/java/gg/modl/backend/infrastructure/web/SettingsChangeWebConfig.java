package gg.modl.backend.infrastructure.web;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.settings.controller.SettingsChangeInvalidation;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class SettingsChangeWebConfig implements WebMvcConfigurer {
    private static final String ALL_CHILDREN = "/**";

    private final SettingsChangeInvalidation settingsChangeInvalidation;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(settingsChangeInvalidation).addPathPatterns(
            RESTMappingV1.PANEL_SETTINGS + ALL_CHILDREN,
            RESTMappingV1.PANEL_UPDATES + ALL_CHILDREN);
    }
}
