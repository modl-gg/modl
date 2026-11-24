package gg.modl.backend.storage.backend;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

final class StorageRoutes {
    private StorageRoutes() {
    }

    static StorageRoute initial(StorageProperties environment, StorageBackendSettings settings, StorageBackends backends) {
        Optional<StorageType> managed = environment.configuredType();
        if (managed.isPresent()) {
            return managedRoute(managed.get(), environment, settings, backends);
        }
        ObjectStorage active = backends.backend(settings.active(), settings.s3());
        StorageRoute route = settings.migrationTarget()
            .<StorageRoute>map(target -> new StorageRoute.Migrating(settings.active(), active, target, backends.backend(target, settings.s3())))
            .orElseGet(() -> new StorageRoute.Stable(settings.active(), active));
        return route.withRetiredCopies(oldCopies(settings, backends));
    }

    static List<ObjectStorage> oldCopies(StorageBackendSettings settings, StorageBackends backends) {
        return settings.oldCopies().stream().map(backends::backend).toList();
    }

    static List<Function<String, Optional<String>>> formerUrlParsers(StorageBackendSettings settings) {
        return settings.formerPublicBaseUrls().stream().<Function<String, Optional<String>>>map(FormerPublicBase::new).toList();
    }

    private static StorageRoute.Stable managedRoute(StorageType type, StorageProperties environment, StorageBackendSettings settings,
                                                    StorageBackends backends) {
        if (type == StorageType.S3 && !environment.s3().complete()) {
            throw new IllegalStateException("MODL_STORAGE=s3 requires " + String.join(", ", environmentNames(environment.s3())));
        }
        StorageBackendAddress managed = StorageBackendAddress.of(type, environment.s3());
        List<ObjectStorage> panelChosen = settings.liveAddresses().stream()
            .filter(address -> !address.sameBackend(managed))
            .map(backends::backend)
            .toList();
        List<ObjectStorage> oldCopies = settings.oldCopies().stream()
            .filter(address -> !address.sameBackend(managed))
            .map(backends::backend)
            .toList();
        return new StorageRoute.Stable(type, backends.backend(managed), panelChosen, oldCopies);
    }

    private static List<String> environmentNames(StorageProperties.S3 s3) {
        return s3.missingSettings().stream().map(setting -> switch (setting) {
            case "bucket" -> "MODL_S3_BUCKET";
            case "access key" -> "MODL_S3_ACCESS_KEY";
            default -> "MODL_S3_SECRET_KEY";
        }).toList();
    }
}
