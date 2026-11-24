package gg.modl.backend.update;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.UpdateFiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UpdateConfiguration {
    @Bean
    UpdateFiles updateFiles(DataDirectoryProperties dataDirectory) {
        return new UpdateFiles(dataDirectory.root(), JacksonJsonMaps.INSTANCE);
    }

    @Bean
    ReleaseKeys releaseKeys() {
        return ReleaseKeys.bundled();
    }

    @Bean
    ReleaseBuild releaseBuild() {
        return ReleaseBuild.current();
    }
}
