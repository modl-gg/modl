package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.archive.ArchiveManifest;
import gg.modl.backend.hostedimport.archive.FoldedDates;
import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.archive.NdjsonLines;
import gg.modl.backend.hostedimport.archive.UnreadableDocumentException;
import gg.modl.backend.hostedimport.archive.VerifiedEntryStream;
import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.storage.ImportedKeyPlan;
import gg.modl.backend.log.service.CredentialCommandRedactor;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class ArchiveAnalyzer {
    private static final JsonMapper PLAIN_JSON = JsonMapper.builder().build();
    private static final Set<String> ENFORCING = Set.of("BAN", "MUTE");
    private static final Comparator<ArchiveDocument> SETTINGS_PRECEDENCE = Comparator
        .comparing((ArchiveDocument document) -> Objects.requireNonNullElse(document.longValue("version"), Long.MIN_VALUE))
        .thenComparing(document -> Objects.requireNonNullElse(document.instant("updatedAt"), Instant.MIN))
        .thenComparing(document -> Objects.requireNonNullElse(document.id(), ""));

    private final HostedArchive archive;
    private final ArchiveManifest manifest;
    private final Instant now;
    private final Instant ipCutoff;
    private final BooleanSupplier stopRequested;
    private final IssueLog issues = new IssueLog();
    private final Map<String, ArchiveDocument> settings = new HashMap<>();
    private final List<ArchiveDocument> roles = new ArrayList<>();
    private final List<ArchiveDocument> staff = new ArrayList<>();
    private final List<ArchiveDocument> categories = new ArrayList<>();
    private final List<ArchiveDocument> cards = new ArrayList<>();
    private final UuidSet seenPlayers = new UuidSet();
    private final SourcePlayerIndex sourcePlayers = new SourcePlayerIndex();
    private final PunishmentKeepers keepers = new PunishmentKeepers();
    private final Set<Integer> referencedOrdinals = new TreeSet<>();
    private final Map<Integer, Long> enforcedByOrdinal = new TreeMap<>();
    private final List<String> ticketIds = new ArrayList<>();
    private final Map<String, ArchiveIndex.StorageFileRecord> storageFiles = new HashMap<>();
    private final AgeHistogram chatAges;
    private final AgeHistogram commandAges;
    private final AgeHistogram auditAges;
    private final AgeHistogram replayAges;
    private long articleCount;
    private long ipEntriesBeyondCutoff;

    public ArchiveAnalyzer(HostedArchive archive, ArchiveManifest manifest, Instant now, int ipHistoryDays,
                           BooleanSupplier stopRequested) {
        this.archive = archive;
        this.manifest = manifest;
        this.now = now;
        this.ipCutoff = now.minus(Duration.ofDays(ipHistoryDays));
        this.stopRequested = stopRequested;
        this.chatAges = new AgeHistogram(now);
        this.commandAges = new AgeHistogram(now);
        this.auditAges = new AgeHistogram(now);
        this.replayAges = new AgeHistogram(now);
    }

    public ArchiveIndex analyse(boolean verifyObjects, Consumer<Long> progress) {
        verifyEntrySet();
        ArchivedServer server = readServer();
        long done = 0;
        for (ArchiveManifest.CollectionRecord collection : manifest.collections()) {
            readCollection(collection);
            done += collection.size();
            progress.accept(done);
        }
        if (verifyObjects) {
            for (ArchiveManifest.StoredObjectRecord object : manifest.objects()) {
                requireRunning();
                try (VerifiedEntryStream stream = archive.open(object.path(), object.size(), object.sha256())) {
                    stream.drainAndVerify();
                } catch (IOException e) {
                    throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
                }
                done += object.size();
                progress.accept(done);
            }
        }
        reportManifestFacts();
        ImportedKeyPlan keyPlan = ImportedKeyPlan.of(manifest, issues);
        return new ArchiveIndex(manifest, server, Map.copyOf(settings), List.copyOf(roles), List.copyOf(staff),
            List.copyOf(categories), List.copyOf(cards), articleCount,
            sourcePlayers, keepers, Set.copyOf(referencedOrdinals), Map.copyOf(enforcedByOrdinal),
            List.copyOf(ticketIds), Map.copyOf(storageFiles), keyPlan,
            new ArchiveIndex.RetentionFacts(chatAges, commandAges, auditAges, replayAges, ipEntriesBeyondCutoff,
                bytesOf(ArchiveCollection.CHAT_LOGS), bytesOf(ArchiveCollection.COMMAND_LOGS), bytesOf(ArchiveCollection.LOGS),
                bytesOf(ArchiveCollection.REPLAYS)),
            issues.tally(), now);
    }

    private void verifyEntrySet() {
        Set<String> expected = new HashSet<>();
        expected.add(HostedArchive.MANIFEST);
        expected.add(manifest.server().path());
        manifest.collections().forEach(collection -> expected.add(collection.path()));
        manifest.objects().forEach(object -> expected.add(object.path()));
        int expectedCount = 2 + manifest.collections().size() + manifest.objects().size();
        if (expected.size() != expectedCount || archive.entryCount() != expectedCount
            || !expected.stream().allMatch(archive::contains)) {
            throw new ImportFailure(ImportReason.ARCHIVE_ENTRIES);
        }
    }

    private ArchivedServer readServer() {
        ArchiveManifest.EntryRecord record = manifest.server();
        try (VerifiedEntryStream stream = archive.open(record.path(), record.size(), record.sha256())) {
            byte[] bytes = stream.readAllBytes();
            Map<?, ?> server = PLAIN_JSON.readValue(bytes, Map.class);
            return new ArchivedServer(
                server.get("serverName") instanceof String name ? name : null,
                server.get("adminEmail") instanceof String email ? email : null);
        } catch (JacksonException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_FORMAT, e);
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
    }

    private void readCollection(ArchiveManifest.CollectionRecord record) {
        ArchiveCollection collection = ArchiveCollection.fromArchiveName(record.name()).orElse(null);
        if (collection == null) {
            issues.report(IssueCode.COLLECTION_UNKNOWN, record.name());
        }
        try (VerifiedEntryStream stream = archive.open(record.path(), record.size(), record.sha256())) {
            try {
                readDocuments(collection, record, new NdjsonLines(stream));
            } catch (ImportFailure | UncheckedIOException expected) {
                throw expected;
            } catch (RuntimeException unexpected) {
                throw stream.integrityFailureOr(unexpected);
            }
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        } catch (UncheckedIOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
    }

    private void readDocuments(@Nullable ArchiveCollection collection, ArchiveManifest.CollectionRecord record,
                               NdjsonLines lines) {
        while (lines.next()) {
            if ((lines.lineNumber() & 0x3ff) == 0) {
                requireRunning();
            }
            if (collection != null) {
                readDocument(collection, record.name(), lines);
            }
        }
        if (lines.lineNumber() != record.documents()) {
            throw new ImportFailure(ImportReason.ARCHIVE_CHECKSUM);
        }
    }

    private static boolean isEnforcing(@Nullable String category) {
        return category != null && ENFORCING.contains(category);
    }

    private void readDocument(ArchiveCollection collection, String name, NdjsonLines lines) {
        ArchiveDocument document;
        try {
            document = ArchiveDocument.of(lines.decode());
        } catch (UnreadableDocumentException e) {
            issues.report(e.tooLarge() ? IssueCode.RECORD_TOO_LARGE : IssueCode.RECORD_UNREADABLE, name + ":" + lines.lineNumber());
            return;
        }
        switch (collection) {
            case CHAT_LOGS -> logTime(chatAges, document, ImportSubject.CHAT_LOGS);
            case COMMAND_LOGS -> command(document);
            case LOGS -> {
                auditAges.add(timeOr(document.instant("created")));
                issues.seen(ImportSubject.AUDIT_LOGS, 1);
            }
            case HOMEPAGE_CARDS -> cards.add(document);
            case ARTICLES -> articleCount++;
            case CATEGORIES -> categories.add(document);
            case PLAYERS -> player(document);
            case REPLAYS -> {
                replayAges.add(timeOr(document.instant("createdAt")));
                issues.seen(ImportSubject.REPLAYS, 1);
            }
            case SETTINGS -> setting(document);
            case STAFF_ROLES -> roles.add(document);
            case STAFF -> staff.add(document);
            case STORAGE_FILES -> storageFile(document);
            case TICKETS -> ticket(document);
        }
    }

    private void logTime(AgeHistogram ages, ArchiveDocument document, ImportSubject subject) {
        ages.add(LogTimes.sentAt(document.raw("timestamp"), exportTime(), now));
        issues.seen(subject, 1);
    }

    private void command(ArchiveDocument document) {
        logTime(commandAges, document, ImportSubject.COMMAND_LOGS);
        String command = document.string("command");
        if (command != null && !CredentialCommandRedactor.redact(command).equals(command)) {
            issues.report(IssueCode.COMMAND_REDACTED, "");
        }
    }

    private void player(ArchiveDocument document) {
        UUID uuid = document.uuid("minecraftUuid");
        String sourceId = document.id();
        if (uuid == null) {
            issues.report(IssueCode.PLAYER_INVALID_UUID, Objects.requireNonNullElse(sourceId, ""));
            return;
        }
        String key = uuid.toString();
        issues.seen(ImportSubject.PLAYERS, 1);
        if (sourceId != null) {
            sourcePlayers.putIfAbsent(sourceId, uuid);
        }
        if (!seenPlayers.add(uuid)) {
            issues.report(IssueCode.PLAYER_MERGED, key);
        }
        for (ArchiveDocument address : document.documents("ipAddresses")) {
            Instant lastSeen = address.instant("firstLogin");
            if (address.raw("logins") instanceof FoldedDates logins && logins.latest() != null
                && (lastSeen == null || logins.latest().isAfter(lastSeen))) {
                lastSeen = logins.latest();
            }
            if (lastSeen == null) {
                lastSeen = exportTime();
            }
            if (lastSeen.isBefore(ipCutoff)) {
                ipEntriesBeyondCutoff++;
            }
        }
        for (ArchiveDocument punishment : document.documents("punishments")) {
            punishment(punishment, key);
        }
    }

    private void punishment(ArchiveDocument punishment, String uuid) {
        issues.seen(ImportSubject.PUNISHMENTS, 1);
        String id = punishment.id();
        if (id != null) {
            keepers.offer(id, uuid);
        }
        Integer ordinal = punishment.intValue("typeOrdinal");
        if (ordinal != null) {
            referencedOrdinals.add(ordinal);
        }
        ArchiveDocument data = punishment.document("data").orElse(null);
        if (data == null) {
            issues.report(IssueCode.PUNISHMENT_WITHOUT_DATA, Objects.requireNonNullElse(id, ""));
        } else {
            if (ordinal != null && isEnforcing(data.string("enforcementCategory"))) {
                enforcedByOrdinal.merge(ordinal, 1L, Long::sum);
            }
            if (data.bool("wipeAfterExpiry") && !data.bool("statWipeCompleted")) {
                issues.report(IssueCode.STAT_WIPE_PENDING, Objects.requireNonNullElse(id, ""));
            }
        }
        boolean rolledBack = punishment.documents("modifications").stream()
            .anyMatch(modification -> PunishmentModificationType.fromName(modification.string("type")) == PunishmentModificationType.ROLLBACK);
        if (rolledBack) {
            issues.report(IssueCode.PUNISHMENT_ROLLBACK_ENDS, Objects.requireNonNullElse(id, ""));
        }
    }

    private void setting(ArchiveDocument document) {
        String type = document.string("type");
        if (type == null) {
            issues.report(IssueCode.RECORD_UNREADABLE, "");
            return;
        }
        settings.merge(type, document, (first, second) -> SETTINGS_PRECEDENCE.compare(first, second) >= 0 ? first : second);
    }

    private void storageFile(ArchiveDocument document) {
        String key = document.string("key");
        if (key != null) {
            storageFiles.put(key, new ArchiveIndex.StorageFileRecord(document.string("fileName"), document.instant("createdAt")));
        }
    }

    private void ticket(ArchiveDocument document) {
        issues.seen(ImportSubject.TICKETS, 1);
        String id = document.id();
        if (id != null) {
            ticketIds.add(id);
        }
        document.document("aiAnalysis")
            .flatMap(analysis -> analysis.document("suggestedAction"))
            .map(action -> action.intValue("punishmentTypeId"))
            .ifPresent(referencedOrdinals::add);
    }

    private void reportManifestFacts() {
        manifest.skippedObjects().forEach(skipped -> issues.report(IssueCode.FILE_SKIPPED_BY_EXPORT, skipped.key()));
        issues.seen(ImportSubject.FILES, manifest.objects().size());
        issues.seen(ImportSubject.STAFF, staff.size());
        issues.seen(ImportSubject.ROLES, roles.stream().filter(RoleMapper::isImported).count());
        issues.seen(ImportSubject.CATEGORIES, categories.size());
        issues.seen(ImportSubject.ARTICLES, articleCount);
        issues.seen(ImportSubject.CARDS, cards.size());
        issues.seen(ImportSubject.SETTINGS, settings.keySet().stream().filter(SettingsImport.IMPORTED::contains).count());
    }

    private Instant timeOr(@Nullable Instant value) {
        return LogTimes.clamp(value, exportTime(), now);
    }

    private Instant exportTime() {
        return manifest.createdAt() != null && manifest.createdAt().isBefore(now) ? manifest.createdAt() : now;
    }

    private long bytesOf(ArchiveCollection collection) {
        return manifest.collections().stream()
            .filter(record -> record.name().equals(collection.archiveName()))
            .mapToLong(ArchiveManifest.CollectionRecord::size)
            .sum();
    }

    private void requireRunning() {
        if (stopRequested.getAsBoolean()) {
            throw new ImportFailure(ImportReason.CANCELLED);
        }
    }
}
