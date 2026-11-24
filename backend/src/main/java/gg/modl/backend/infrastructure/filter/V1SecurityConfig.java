package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.ratelimit.AccountRateLimitFilter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import gg.modl.backend.storage.StorageOrigins;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class V1SecurityConfig {
    private static final String ALL_CHILDREN = "/**";
    private static final String ACTUATOR = "/actuator";
    private static final String ACTUATOR_HEALTH = ACTUATOR + "/health";

    private final SessionAuthenticationFilter sessionAuthenticationFilter;
    private final ApiKeyFilter apiKeyFilter;
    private final PanelPermissionFilter panelPermissionFilter;
    private final OriginCsrfFilter originCsrfFilter;
    private final AccountRateLimitFilter accountRateLimitFilter;
    private final StorageOrigins storageOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .cors(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .headers(headers -> headers
                .frameOptions(frameOptions -> frameOptions.deny())
                .contentTypeOptions(contentTypeOptions -> {})
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .httpStrictTransportSecurity(hsts -> hsts.disable())
                .addHeaderWriter(new PanelContentSecurityPolicyWriter(storageOrigins))
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new UnauthenticatedResponse()))
            .authorizeHttpRequests(c -> c
                .requestMatchers(HttpMethod.OPTIONS, ALL_CHILDREN).permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(ACTUATOR_HEALTH, ACTUATOR_HEALTH + ALL_CHILDREN).permitAll()
                .requestMatchers(HttpMethod.HEAD, RESTMappingV1.V1).permitAll()
                .requestMatchers(RESTMappingV1.HEALTH).permitAll()
                .requestMatchers(RESTMappingV1.STORAGE_OBJECTS + ALL_CHILDREN).permitAll()
                .requestMatchers(RESTMappingV1.PREFIX_PUBLIC + ALL_CHILDREN).permitAll()
                .requestMatchers(HttpMethod.POST, RESTMappingV1.PANEL_AUTH_ANONYMOUS_ENDPOINTS.toArray(String[]::new)).permitAll()
                .requestMatchers(RESTMappingV1.REALTIME_WS).permitAll()
                .requestMatchers(RESTMappingV1.PREFIX_PANEL + ALL_CHILDREN).hasAuthority(RESTSecurityRole.USER)
                .requestMatchers(
                    RESTMappingV1.PREFIX_MINECRAFT + ALL_CHILDREN,
                    RESTMappingV2.PREFIX_MINECRAFT + ALL_CHILDREN,
                    RESTMappingV3.PREFIX_MINECRAFT + ALL_CHILDREN
                ).hasAnyAuthority(RESTSecurityRole.MINECRAFT, RESTSecurityRole.MINECRAFT_MEMBER)
                .requestMatchers(
                    RESTMappingV1.V1 + ALL_CHILDREN,
                    RESTMappingV2.PREFIX + ALL_CHILDREN,
                    RESTMappingV3.PREFIX + ALL_CHILDREN,
                    ACTUATOR + ALL_CHILDREN
                ).denyAll()
                .requestMatchers(new StaticContentRequestMatcher()).permitAll()
                .anyRequest().denyAll()
            )
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(accountRateLimitFilter, SessionAuthenticationFilter.class)
            .addFilterAfter(panelPermissionFilter, AccountRateLimitFilter.class)
            .addFilterBefore(originCsrfFilter, SessionAuthenticationFilter.class)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .build();
    }
}
