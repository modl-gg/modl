package gg.modl.backend.hostedimport.repository;

import gg.modl.backend.database.JsonColumns;
import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.settings.data.SettingsType;
import gg.modl.backend.settings.repository.SettingsRepository;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
public class SettingsHostedImportStateRepository implements HostedImportStateRepository {
    static final String STATE = SettingsType.HOSTED_IMPORT.id();
    static final String CHOICES = SettingsType.HOSTED_IMPORT_CHOICES.id();
    static final String ALIASES = SettingsType.HOSTED_IMPORT_ALIASES.id();
    private static final TypeReference<TreeMap<String, String>> ALIAS_MAP = new TypeReference<>() {
    };

    private final SettingsRepository settingsRepository;
    private final JsonColumns json;

    public SettingsHostedImportStateRepository(SettingsRepository settingsRepository, JsonColumns json) {
        this.settingsRepository = settingsRepository;
        this.json = json;
    }

    @Override
    public Optional<StoredJob> find() {
        return settingsRepository.find(STATE)
            .flatMap(row -> Optional.ofNullable(json.read(row.dataJson(), ImportJob.class)).map(job -> new StoredJob(job, row.version())));
    }

    @Override
    public boolean insert(ImportJob job) {
        return settingsRepository.insertIfAbsent(STATE, json.write(job));
    }

    @Override
    public boolean replace(long expectedVersion, ImportJob job) {
        return settingsRepository.updateIfVersion(STATE, expectedVersion, json.write(job));
    }

    @Override
    public Optional<ImportChoices> choices() {
        return settingsRepository.find(CHOICES).map(row -> json.read(row.dataJson(), ImportChoices.class));
    }

    @Override
    public void saveChoices(ImportChoices choices) {
        settingsRepository.upsert(CHOICES, json.write(choices));
    }

    @Override
    public Map<String, String> aliases() {
        return settingsRepository.find(ALIASES).<Map<String, String>>map(row -> json.read(row.dataJson(), ALIAS_MAP)).orElseGet(Map::of);
    }

    @Override
    public void saveAliases(Map<String, String> aliases) {
        settingsRepository.upsert(ALIASES, json.write(new TreeMap<>(aliases)));
    }

    @Override
    public void deleteAll() {
        settingsRepository.delete(STATE);
        settingsRepository.delete(CHOICES);
        settingsRepository.delete(ALIASES);
    }
}
