package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.storage.ImportedKeyPlan;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayLabel;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ReplayMapper {
    private static final int ID_LIMIT = 64;
    private static final int MAX_LABELS = 100;
    private static final int MAX_CHEATS = 20;
    private static final int MAX_RANGES = 50;
    private static final long MAX_RANGE_MS = 24L * 60L * 60L * 1000L;

    private final ImportContext context;
    private final ImportFit fit;

    public ReplayMapper(ImportContext context) {
        this.context = context;
        this.fit = context.fit();
    }

    public Optional<ReplayDocument> map(ArchiveDocument source) {
        String sourceId = source.id();
        if (sourceId == null) {
            context.issues().skipped(ImportSubject.REPLAYS);
            return Optional.empty();
        }
        ReplayDocument replay = new ReplayDocument();
        replay.setId(context.ids().adopt(IdKind.REPLAY, "", sourceId, ID_LIMIT));
        replay.setMcVersion(fit.columnIfPresent(REPLAYS.MC_VERSION, source.text("mcVersion")));
        UUID target = source.uuid("targetUuid");
        replay.setTargetUuid(target == null ? null : target.toString());
        replay.setTargetName(fit.columnIfPresent(REPLAYS.TARGET_NAME, source.text("targetName")));
        replay.setCreatedAt(Objects.requireNonNullElse(source.instant("createdAt"), context.exportTime()));
        Optional<ImportedKeyPlan.PlannedObject> object = Optional.ofNullable(source.string("storageKey"))
            .flatMap(context.index().keyPlan()::forOriginalKey);
        boolean complete = ReplayDocument.STATUS_COMPLETE.equals(source.string("status")) && object.isPresent();
        if (!complete && !ReplayDocument.STATUS_FAILED.equals(source.string("status"))) {
            context.issues().report(IssueCode.REPLAY_FILE_MISSING, sourceId);
        }
        replay.setStatus(complete ? ReplayDocument.STATUS_COMPLETE : ReplayDocument.STATUS_FAILED);
        replay.setStorageKey(object.map(ImportedKeyPlan.PlannedObject::localKey).orElse(null));
        replay.setFileSize(object.map(planned -> planned.source().size()).orElse(0L));
        replay.setLabels(labels(source.documents("labels")));
        context.issues().imported(ImportSubject.REPLAYS);
        return Optional.of(replay);
    }

    private List<ReplayLabel> labels(List<ArchiveDocument> sources) {
        List<ReplayLabel> labels = new ArrayList<>();
        for (ArchiveDocument source : sources.subList(0, Math.min(sources.size(), MAX_LABELS))) {
            String uuid = fit.cutIfPresent(source.text("uuid"), 64);
            String verdict = fit.cutIfPresent(source.text("verdict"), 32);
            if (uuid == null || uuid.isBlank() || verdict == null || verdict.isBlank()) {
                continue;
            }
            ReplayLabel label = new ReplayLabel();
            label.setUuid(uuid);
            label.setPlayerName(fit.cutIfPresent(source.text("playerName"), 64));
            label.setVerdict(verdict);
            label.setConfidence((int) Math.clamp(Objects.requireNonNullElse(source.longValue("confidence"), 0L), 0L, 100L));
            label.setNotes(fit.cutIfPresent(source.string("notes"), 2000));
            label.setCheats(cheats(source.documents("cheats")));
            labels.add(label);
        }
        return labels;
    }

    private List<ReplayLabel.CheatDetail> cheats(List<ArchiveDocument> sources) {
        List<ReplayLabel.CheatDetail> cheats = new ArrayList<>();
        for (ArchiveDocument source : sources.subList(0, Math.min(sources.size(), MAX_CHEATS))) {
            String type = fit.cutIfPresent(source.text("type"), 64);
            if (type == null || type.isBlank()) {
                continue;
            }
            ReplayLabel.CheatDetail cheat = new ReplayLabel.CheatDetail();
            cheat.setType(type);
            List<ReplayLabel.TimeRange> ranges = new ArrayList<>();
            for (ArchiveDocument range : source.documents("timeRanges")) {
                if (ranges.size() >= MAX_RANGES) {
                    break;
                }
                long start = Math.clamp(Objects.requireNonNullElse(range.longValue("startMs"), 0L), 0L, MAX_RANGE_MS);
                long end = Math.clamp(Objects.requireNonNullElse(range.longValue("endMs"), start), start, MAX_RANGE_MS);
                ReplayLabel.TimeRange timeRange = new ReplayLabel.TimeRange();
                timeRange.setStartMs(start);
                timeRange.setEndMs(end);
                ranges.add(timeRange);
            }
            cheat.setTimeRanges(ranges);
            cheats.add(cheat);
        }
        return cheats;
    }
}
