package gg.modl.backend.update.commit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class UpdateCommitConfiguration {
    @Bean
    FilterRegistrationBean<UpdateCommitGateFilter> updateCommitGateFilter(UpdateGate gate) {
        FilterRegistrationBean<UpdateCommitGateFilter> registration = new FilterRegistrationBean<>(new UpdateCommitGateFilter(gate));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
