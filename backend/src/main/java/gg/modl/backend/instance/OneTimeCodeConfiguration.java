package gg.modl.backend.instance;

import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OneTimeCodeConfiguration {

    @Bean
    OneTimeCodeCodec oneTimeCodeCodec(InstanceSecretsService instanceSecretsService) {
        return new OneTimeCodeCodec(instanceSecretsService::authCodeHashSecret);
    }
}
