package gg.modl.backend.hostedimport.controller;

import com.google.protobuf.Timestamp;
import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.model.ArchiveSummary;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueEntry;
import gg.modl.backend.hostedimport.model.IssueSeverity;
import gg.modl.backend.hostedimport.model.RetentionChoice;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import gg.modl.backend.hostedimport.model.SubjectCount;
import gg.modl.backend.hostedimport.service.ImportPreview;
import gg.modl.backend.hostedimport.service.ImportStateView;
import gg.modl.proto.modl.v1.HostedImportCount;
import gg.modl.proto.modl.v1.HostedImportExclusion;
import gg.modl.proto.modl.v1.HostedImportIssue;
import gg.modl.proto.modl.v1.HostedImportIssueCode;
import gg.modl.proto.modl.v1.HostedImportPhase;
import gg.modl.proto.modl.v1.HostedImportPreview;
import gg.modl.proto.modl.v1.HostedImportReport;
import gg.modl.proto.modl.v1.HostedImportRetention;
import gg.modl.proto.modl.v1.HostedImportRetentionPlan;
import gg.modl.proto.modl.v1.HostedImportSeverity;
import gg.modl.proto.modl.v1.HostedImportStaffCandidate;
import gg.modl.proto.modl.v1.HostedImportStaffPage;
import gg.modl.proto.modl.v1.HostedImportState;
import gg.modl.proto.modl.v1.HostedImportStatus;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class HostedImportProtoMapper {
    static final List<String> FOLLOW_UPS = List.of("apiKey", "passkeys", "invitations", "address", "mail", "webhooks", "ai", "minecraft",
        "oldLinks");
    private static final String ISSUE_CODE_PREFIX = "HOSTED_IMPORT_ISSUE_CODE_";

    private HostedImportProtoMapper() {
    }

    static HostedImportState toState(ImportStateView view) {
        HostedImportState.Builder state = HostedImportState.newBuilder()
            .setEligible(view.eligible())
            .addAllBlockers(view.blockers())
            .setMaxChunkBytes(view.maxChunkBytes())
            .setStatus(HostedImportStatus.HOSTED_IMPORT_STATUS_NONE);
        view.job().ifPresent(job -> state
            .setStatus(toProto(job.status()))
            .setImportId(job.id())
            .setPhase(toProto(job.phase()))
            .setReceivedBytes(job.receivedBytes())
            .setTotalBytes(job.sizeBytes())
            .setPhaseDone(job.phaseDone())
            .setPhaseTotal(job.phaseTotal())
            .setReasonCode(job.reason() == null ? "" : job.reason().name())
            .setRequiredBytes(job.requiredBytes())
            .setArchiveSha256(Objects.requireNonNullElse(job.archiveSha256(), ""))
            .setCanDiscard(job.canDiscard())
            .setCanResume(job.canResume())
            .setCanAbandon(job.canAbandon())
            .setCanFinish(job.canFinish())
            .setUpdatedAt(timestamp(job.updatedAt()))
            .setFileName(Objects.requireNonNullElse(job.fileName(), "")));
        return state.build();
    }

    static HostedImportPreview toPreview(ImportPreview preview) {
        ImportJob job = preview.job();
        ArchiveSummary archive = job.archive();
        HostedImportPreview.Builder proto = HostedImportPreview.newBuilder()
            .setImportId(job.id())
            .setArchivedOwnerEmail(Objects.requireNonNullElse(preview.archivedOwnerEmail(), ""))
            .setLocalOwnerEmail(preview.localOwnerEmail())
            .setOwnerEmailsMatch(preview.ownerEmailsMatch())
            .addAllCounts(counts(preview.tally()))
            .addAllIssues(issues(preview.tally()))
            .addAllRetention(preview.retention().stream().map(HostedImportProtoMapper::toProto).toList())
            .setRequiredBytes(job.requiredBytes())
            .setAvailableBytes(preview.availableBytes())
            .setStaffTotal(preview.staff().size())
            .setArchivedOwnerMinecraftUsername(Objects.requireNonNullElse(preview.archivedOwnerMinecraftUsername(), ""))
            .setArchivedOwnerHasStaffRecord(preview.archivedOwnerHasStaffRecord());
        if (archive != null) {
            proto.setServerName(Objects.requireNonNullElse(archive.serverName(), ""))
                .setSubdomain(Objects.requireNonNullElse(archive.subdomain(), ""));
            optionalTimestamp(archive.exportedAt()).ifPresent(proto::setExportedAt);
        }
        return proto.build();
    }

    static HostedImportStaffPage toStaffPage(List<StaffPlan.Candidate> page, int offset, int pageSize, int total) {
        HostedImportStaffPage.Builder proto = HostedImportStaffPage.newBuilder();
        page.forEach(candidate -> proto.addStaff(HostedImportStaffCandidate.newBuilder()
            .setSourceId(candidate.sourceId())
            .setEmail(candidate.email())
            .setUsername(candidate.username())
            .setRoleName(candidate.roleName())
            .addAllPermissions(candidate.permissions())
            .setMinecraftUuid(Objects.requireNonNullElse(candidate.minecraftUuid(), ""))
            .setMinecraftUsername(Objects.requireNonNullElse(candidate.minecraftUsername(), ""))
            .setSelectable(candidate.selectable())
            .setBlockedCode(Objects.requireNonNullElse(candidate.blockedCode(), ""))));
        int next = offset + pageSize;
        proto.setNextCursor(next < total ? Integer.toString(next) : "");
        return proto.build();
    }

    static HostedImportReport toReport(ImportJob job, @Nullable RetentionChoice retention) {
        ArchiveSummary archive = Objects.requireNonNull(job.archive());
        HostedImportReport.Builder report = HostedImportReport.newBuilder()
            .setImportId(job.id())
            .setStatus(toProto(job.status()))
            .setExportId(archive.exportId())
            .setSourceVersion(archive.sourceVersion())
            .addAllCounts(counts(job.tally()))
            .addAllIssues(issues(job.tally()))
            .addAllExcludedCollections(exclusions(archive.excludedCollections()))
            .addAllSkippedObjects(exclusions(archive.skippedObjects()))
            .addAllFollowUpCodes(FOLLOW_UPS)
            .setRetention(retention == null ? HostedImportRetention.HOSTED_IMPORT_RETENTION_UNSPECIFIED : toProto(retention));
        optionalTimestamp(archive.exportedAt()).ifPresent(report::setExportedAt);
        optionalTimestamp(job.finishedAt()).ifPresent(report::setFinishedAt);
        return report.build();
    }

    static RetentionChoice fromProto(HostedImportRetention retention) {
        return switch (retention) {
            case HOSTED_IMPORT_RETENTION_KEEP_HISTORY -> RetentionChoice.KEEP_HISTORY;
            case HOSTED_IMPORT_RETENTION_STANDARD -> RetentionChoice.STANDARD;
            case HOSTED_IMPORT_RETENTION_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalArgumentException("A retention choice is required");
        };
    }

    private static List<HostedImportCount> counts(ImportTally tally) {
        return tally.counts().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> count(entry.getKey(), entry.getValue()))
            .toList();
    }

    private static HostedImportCount count(ImportSubject subject, SubjectCount count) {
        return HostedImportCount.newBuilder()
            .setSubject(subject.id())
            .setTotal(Math.max(count.total(), Math.max(0, count.imported()) + count.skipped()))
            .setImported(Math.max(0, count.imported()))
            .setChanged(count.changed())
            .setSkipped(count.skipped())
            .build();
    }

    private static List<HostedImportIssue> issues(ImportTally tally) {
        return tally.issues().entrySet().stream()
            .filter(entry -> entry.getValue().count() > 0)
            .sorted(Comparator.comparing((Map.Entry<IssueCode, IssueEntry> entry) -> entry.getKey().severity()).reversed()
                .thenComparing(Map.Entry::getKey))
            .map(entry -> HostedImportIssue.newBuilder()
                .setCode(toProto(entry.getKey()))
                .setSeverity(toProto(entry.getKey().severity()))
                .setCount(entry.getValue().count())
                .addAllSamples(entry.getValue().samples())
                .build())
            .toList();
    }

    private static List<HostedImportExclusion> exclusions(List<ArchiveSummary.NamedCount> values) {
        return values.stream().map(value -> HostedImportExclusion.newBuilder()
            .setName(value.name())
            .setReason(value.reason())
            .setApproximateCount(value.count())
            .build()).toList();
    }

    private static HostedImportRetentionPlan toProto(RetentionPlan plan) {
        return HostedImportRetentionPlan.newBuilder()
            .setOption(toProto(plan.choice()))
            .setChatLogDays(plan.chatLogDays())
            .setAuditLogDays(plan.auditLogDays())
            .setReplayRetentionEnabled(plan.replayRetentionEnabled())
            .setReplayDays(plan.replayDays())
            .setRowsNotKept(plan.rowsNotKept())
            .setEstimatedBytes(plan.estimatedBytes())
            .setKeepsIpHistory(plan.keepsIpHistory())
            .setReplaysNotKept(plan.replaysNotKept())
            .setRequiredBytes(plan.requiredBytes())
            .build();
    }

    private static HostedImportRetention toProto(RetentionChoice choice) {
        return switch (choice) {
            case KEEP_HISTORY -> HostedImportRetention.HOSTED_IMPORT_RETENTION_KEEP_HISTORY;
            case STANDARD -> HostedImportRetention.HOSTED_IMPORT_RETENTION_STANDARD;
        };
    }

    static HostedImportIssueCode toProto(IssueCode code) {
        return HostedImportIssueCode.valueOf(ISSUE_CODE_PREFIX + code.name());
    }

    private static HostedImportSeverity toProto(IssueSeverity severity) {
        return switch (severity) {
            case INFO -> HostedImportSeverity.HOSTED_IMPORT_SEVERITY_INFO;
            case CHANGED -> HostedImportSeverity.HOSTED_IMPORT_SEVERITY_CHANGED;
            case LOST -> HostedImportSeverity.HOSTED_IMPORT_SEVERITY_LOST;
            case ACTION -> HostedImportSeverity.HOSTED_IMPORT_SEVERITY_ACTION;
        };
    }

    private static HostedImportStatus toProto(ImportStatus status) {
        return switch (status) {
            case NONE -> HostedImportStatus.HOSTED_IMPORT_STATUS_NONE;
            case RECEIVING -> HostedImportStatus.HOSTED_IMPORT_STATUS_RECEIVING;
            case DOWNLOADING -> HostedImportStatus.HOSTED_IMPORT_STATUS_DOWNLOADING;
            case VERIFYING -> HostedImportStatus.HOSTED_IMPORT_STATUS_VERIFYING;
            case REVIEW -> HostedImportStatus.HOSTED_IMPORT_STATUS_REVIEW;
            case APPLYING -> HostedImportStatus.HOSTED_IMPORT_STATUS_APPLYING;
            case PAUSED -> HostedImportStatus.HOSTED_IMPORT_STATUS_PAUSED;
            case COMPLETED -> HostedImportStatus.HOSTED_IMPORT_STATUS_COMPLETED;
            case ABANDONED -> HostedImportStatus.HOSTED_IMPORT_STATUS_ABANDONED;
            case FAILED -> HostedImportStatus.HOSTED_IMPORT_STATUS_FAILED;
        };
    }

    private static HostedImportPhase toProto(ImportPhase phase) {
        return switch (phase) {
            case VERIFY -> HostedImportPhase.HOSTED_IMPORT_PHASE_VERIFY;
            case BEGIN -> HostedImportPhase.HOSTED_IMPORT_PHASE_BEGIN;
            case CONFIGURATION -> HostedImportPhase.HOSTED_IMPORT_PHASE_CONFIGURATION;
            case FILES -> HostedImportPhase.HOSTED_IMPORT_PHASE_FILES;
            case PLAYERS -> HostedImportPhase.HOSTED_IMPORT_PHASE_PLAYERS;
            case LINKS -> HostedImportPhase.HOSTED_IMPORT_PHASE_LINKS;
            case TICKETS -> HostedImportPhase.HOSTED_IMPORT_PHASE_TICKETS;
            case REPLAYS -> HostedImportPhase.HOSTED_IMPORT_PHASE_REPLAYS;
            case CHAT_LOGS -> HostedImportPhase.HOSTED_IMPORT_PHASE_CHAT_LOGS;
            case COMMAND_LOGS -> HostedImportPhase.HOSTED_IMPORT_PHASE_COMMAND_LOGS;
            case AUDIT_LOGS -> HostedImportPhase.HOSTED_IMPORT_PHASE_AUDIT_LOGS;
            case ACCESS -> HostedImportPhase.HOSTED_IMPORT_PHASE_ACCESS;
            case SUBSCRIPTIONS -> HostedImportPhase.HOSTED_IMPORT_PHASE_SUBSCRIPTIONS;
            case FINISH -> HostedImportPhase.HOSTED_IMPORT_PHASE_FINISH;
        };
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static Optional<Timestamp> optionalTimestamp(@Nullable Instant instant) {
        return Optional.ofNullable(instant).map(HostedImportProtoMapper::timestamp);
    }
}
