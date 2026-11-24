package gg.modl.backend.storage.backend;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.instance.InstanceSecretsService;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class StorageBackendConfiguration {

    @Bean
    LocalObjectStorage localObjectStorage(DataDirectoryProperties dataDirectory, InstanceSecretsService instanceSecretsService,
                                          Clock clock) {
        return new LocalObjectStorage(dataDirectory.storage(), new UploadSigner(instanceSecretsService::storageSigningKey), clock);
    }

    @Bean(destroyMethod = "close")
    StorageBackends storageBackends(LocalObjectStorage localObjectStorage, Clock clock) {
        return new StorageBackends(localObjectStorage, clock);
    }

    @Bean
    @Primary
    SwitchableObjectStorage objectStorage(StorageProperties environment, StorageBackendSettingsStore settings,
                                          StorageBackends backends) {
        StorageBackendSettings stored = settings.current();
        SwitchableObjectStorage storage = new SwitchableObjectStorage(StorageRoutes.initial(environment, stored, backends));
        storage.recognizeFormerUrls(StorageRoutes.formerUrlParsers(stored));
        return storage;
    }
}
