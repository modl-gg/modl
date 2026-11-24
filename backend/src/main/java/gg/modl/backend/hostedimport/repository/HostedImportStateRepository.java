package gg.modl.backend.hostedimport.repository;

import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.ImportJob;
import java.util.Map;
import java.util.Optional;

public interface HostedImportStateRepository {
    Optional<StoredJob> find();

    boolean insert(ImportJob job);

    boolean replace(long expectedVersion, ImportJob job);

    Optional<ImportChoices> choices();

    void saveChoices(ImportChoices choices);

    Map<String, String> aliases();

    void saveAliases(Map<String, String> aliases);

    void deleteAll();

    record StoredJob(ImportJob job, long version) {
    }
}
