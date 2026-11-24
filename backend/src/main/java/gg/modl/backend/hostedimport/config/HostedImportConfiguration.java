package gg.modl.backend.hostedimport.config;

import gg.modl.backend.hostedimport.intake.HttpArchiveTransport;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.intake.LinkDownloader;
import gg.modl.backend.hostedimport.intake.LinkPolicy;
import gg.modl.backend.infrastructure.net.PublicAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HostedImportConfiguration {
    @Bean(destroyMethod = "close")
    HttpArchiveTransport hostedImportTransport() {
        return new HttpArchiveTransport(PublicAddressResolver.usingSystemDns());
    }

    @Bean
    LinkDownloader hostedImportDownloader(HttpArchiveTransport transport, ImportArchiveFiles files) {
        return new LinkDownloader(transport, files);
    }

    @Bean
    LinkPolicy hostedImportLinkPolicy() {
        return new LinkPolicy();
    }
}
