package gg.modl.backend.infrastructure.web;

import gg.modl.backend.infrastructure.authorization.MemberScopeInterceptor;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class MemberScopeWebConfig implements WebMvcConfigurer {
    private static final String ALL_CHILDREN = "/**";

    private final MemberScopeInterceptor memberScopeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(memberScopeInterceptor).addPathPatterns(
            RESTMappingV1.PREFIX_MINECRAFT + ALL_CHILDREN,
            RESTMappingV2.PREFIX_MINECRAFT + ALL_CHILDREN,
            RESTMappingV3.PREFIX_MINECRAFT + ALL_CHILDREN);
    }
}
