package gg.modl.backend.hostedimport;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.archive.ManifestReader;
import gg.modl.backend.hostedimport.archive.VerifiedEntryStream;
import gg.modl.backend.hostedimport.mapping.ArchiveAnalyzer;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.ImportContext;
import gg.modl.backend.hostedimport.mapping.ImportFit;
import gg.modl.backend.hostedimport.mapping.ImportedIds;
import gg.modl.backend.hostedimport.mapping.ImportedIssuers;
import gg.modl.backend.hostedimport.mapping.ImportedTypes;
import gg.modl.backend.hostedimport.mapping.PunishmentIds;
import gg.modl.backend.hostedimport.mapping.TicketIds;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.storage.ObjectStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ImportTestContexts {
    public static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

    private ImportTestContexts() {
    }

    public static ArchiveIndex index(HostedArchiveFixture fixture, Path directory) throws IOException {
        Path file = fixture.write(directory);
        try (HostedArchive archive = HostedArchive.open(file); VerifiedEntryStream stream = archive.openManifest()) {
            ArchiveManifest manifest = ManifestReader.read(stream);
            return new ArchiveAnalyzer(archive, manifest, NOW, 365, () -> false).analyse(true, done -> { });
        }
    }

    public static ObjectStorage storage() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.publicUrl(anyString())).thenAnswer(call -> "/v1/objects/" + call.getArgument(0));
        return storage;
    }

    public static ImportContext context(ArchiveIndex index, List<PunishmentType> types, Map<String, String> restoredStaff, boolean keepsIp) {
        IssueLog issues = new IssueLog();
        ImportedIds ids = new ImportedIds(index.manifest().exportId());
        return new ImportContext(index, ids, new PunishmentIds(ids, index.punishmentKeepers()), new TicketIds(ids, Map.of()),
            new ImportedIssuers(restoredStaff, Map.of("staff-1", "Moderator")), new ImportedTypes(types, List.of(), true),
            new ImportedUrls(index.manifest(), index.keyPlan(), storage(), issues), issues, new ImportFit(issues),
            Instant.parse("2026-11-22T10:15:30.123Z"), NOW, NOW.minus(Duration.ofDays(365)), keepsIp, Duration.ofDays(90));
    }

    public static ImportContext context(ArchiveIndex index) {
        return context(index, DefaultPunishmentTypes.getAll(), Map.of(), false);
    }
}
