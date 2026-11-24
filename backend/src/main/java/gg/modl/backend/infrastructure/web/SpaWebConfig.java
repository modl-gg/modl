package gg.modl.backend.infrastructure.web;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
    private static final String STATIC_LOCATION = "classpath:/static/";
    private static final String ASSETS_LOCATION = STATIC_LOCATION + "assets/";
    private static final Duration IMMUTABLE_ASSET_MAX_AGE = Duration.ofDays(365);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations(ASSETS_LOCATION)
            .setCacheControl(CacheControl.maxAge(IMMUTABLE_ASSET_MAX_AGE).cachePublic().immutable());
        registry.addResourceHandler("/**")
            .addResourceLocations(STATIC_LOCATION)
            .setCacheControl(CacheControl.noCache())
            .setUseLastModified(false)
            .setEtagGenerator(new ContentEtags())
            .resourceChain(false)
            .addResolver(new SpaFallbackResourceResolver());
    }
}
