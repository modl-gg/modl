package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.hostedimport.storage.ImportedKeyPlan;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record ArchiveIndex(
    ArchiveManifest manifest,
    ArchivedServer server,
    Map<String, ArchiveDocument> settingsByType,
    List<ArchiveDocument> roles,
    List<ArchiveDocument> staff,
    List<ArchiveDocument> categories,
    List<ArchiveDocument> cards,
    long articleCount,
    SourcePlayerIndex sourcePlayers,
    PunishmentKeepers punishmentKeepers,
    Set<Integer> referencedOrdinals,
    Map<Integer, Long> enforcedPunishmentsByOrdinal,
    List<String> ticketIds,
    Map<String, StorageFileRecord> storageFiles,
    ImportedKeyPlan keyPlan,
    RetentionFacts retention,
    ImportTally analysisTally,
    Instant analysedAt
) {
    public Optional<ArchiveDocument> setting(String type) {
        return Optional.ofNullable(settingsByType.get(type));
    }

    @Nullable
    public String uuidOfSourcePlayer(@Nullable String sourcePlayerId) {
        return sourcePlayers.uuidOf(sourcePlayerId);
    }

    public long documents(ArchiveCollection collection) {
        return manifest.collections().stream()
            .filter(record -> record.name().equals(collection.archiveName()))
            .mapToLong(ArchiveManifest.CollectionRecord::documents)
            .sum();
    }

    public record StorageFileRecord(@Nullable String fileName, @Nullable Instant createdAt) {
    }

    public record RetentionFacts(
        AgeHistogram chatLogs,
        AgeHistogram commandLogs,
        AgeHistogram auditLogs,
        AgeHistogram replays,
        long ipEntriesBeyondCutoff,
        long chatBytes,
        long commandBytes,
        long auditBytes,
        long replayBytes
    ) {
    }
}
