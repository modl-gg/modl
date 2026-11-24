package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.ratelimit.AccountRateLimitFilter;
import gg.modl.backend.instance.InstanceContext;
import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@RequiredArgsConstructor
public class FilterConfig {
    private final InstanceContext instanceContext;
    private final ApiKeyFilter apiKeyFilter;
    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final PanelPermissionFilter panelPermissionFilter;
    private final OriginCsrfFilter originCsrfFilter;
    private final AccountRateLimitFilter accountRateLimitFilter;

    @Bean
    public FilterRegistrationBean<InstanceRequestFilter> instanceRequestFilter() {
        FilterRegistrationBean<InstanceRequestFilter> registrationBean =
            new FilterRegistrationBean<>(new InstanceRequestFilter(instanceContext));
        registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registrationBean;
    }

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration() {
        return securityChainOnly(apiKeyFilter);
    }

    @Bean
    public FilterRegistrationBean<SessionAuthenticationFilter> sessionAuthenticationFilterRegistration() {
        return securityChainOnly(sessionAuthenticationFilter);
    }

    @Bean
    public FilterRegistrationBean<PanelPermissionFilter> panelPermissionFilterRegistration() {
        return securityChainOnly(panelPermissionFilter);
    }

    @Bean
    public FilterRegistrationBean<OriginCsrfFilter> originCsrfFilterRegistration() {
        return securityChainOnly(originCsrfFilter);
    }

    @Bean
    public FilterRegistrationBean<AccountRateLimitFilter> accountRateLimitFilterRegistration() {
        return securityChainOnly(accountRateLimitFilter);
    }

    private static <T extends Filter> FilterRegistrationBean<T> securityChainOnly(T filter) {
        FilterRegistrationBean<T> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }
}
