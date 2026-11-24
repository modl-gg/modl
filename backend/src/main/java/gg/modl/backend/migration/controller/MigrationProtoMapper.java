package gg.modl.backend.migration.controller;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.migration.data.ExportCounts;
import gg.modl.backend.migration.data.ImportEnding;
import gg.modl.backend.migration.data.IpHistoryRetention;
import gg.modl.backend.migration.data.MigrationStatus;
import gg.modl.backend.migration.data.ScopedPunishments;
import gg.modl.backend.migration.service.ImportOverview;
import gg.modl.proto.modl.v1.ImportEndReason;
import gg.modl.proto.modl.v1.ImportJob;
import gg.modl.proto.modl.v1.ImportJobResponse;
import gg.modl.proto.modl.v1.ImportStatusResponse;
import gg.modl.proto.modl.v1.ImportedIpHistoryChoice;
import gg.modl.proto.modl.v1.ReportExportProgressRequest;
import gg.modl.proto.modl.v1.ScopedPunishmentChoice;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class MigrationProtoMapper {

    public ImportJobResponse toJobResponse(MigrationStatus job, long maxUploadBytes) {
        return ImportJobResponse.newBuilder()
            .setJob(toJob(job))
            .setMaxUploadBytes(maxUploadBytes)
            .build();
    }

    public ImportStatusResponse toStatusResponse(ImportOverview overview) {
        ImportStatusResponse.Builder builder = ImportStatusResponse.newBuilder()
            .setSetupReady(overview.setupReady())
            .setMaxUploadBytes(overview.maxUploadBytes())
            .setIpHistoryDays(overview.ipHistoryDays());
        overview.job().ifPresent(job -> builder.setJob(toJob(job)));
        overview.ipHistoryKeptBefore().ifPresent(line -> builder.setIpHistoryKeptBefore(line.toEpochMilli()));
        return builder.build();
    }

    ExportCounts toExportCounts(ReportExportProgressRequest request) {
        return new ExportCounts(
            request.hasRecordsProcessed() ? request.getRecordsProcessed() : null,
            request.hasTotalRecords() ? request.getTotalRecords() : null,
            request.hasExportFailed() ? request.getExportFailed() : null,
            request.hasExportSkipped() ? request.getExportSkipped() : null);
    }

    ScopedPunishments toScopedPunishments(ScopedPunishmentChoice choice) {
        return switch (choice) {
            case SCOPED_PUNISHMENT_CHOICE_WIDEN -> ScopedPunishments.WIDEN;
            case SCOPED_PUNISHMENT_CHOICE_SKIP -> ScopedPunishments.SKIP;
            case SCOPED_PUNISHMENT_CHOICE_UNSPECIFIED, UNRECOGNIZED ->
                throw new ValidationException("Choose widen or skip");
        };
    }

    IpHistoryRetention toIpHistoryRetention(ImportedIpHistoryChoice choice) {
        return switch (choice) {
            case IMPORTED_IP_HISTORY_CHOICE_KEEP -> IpHistoryRetention.KEEP;
            case IMPORTED_IP_HISTORY_CHOICE_STANDARD -> IpHistoryRetention.STANDARD;
            case IMPORTED_IP_HISTORY_CHOICE_UNSPECIFIED, UNRECOGNIZED ->
                throw new ValidationException("Choose keep or standard");
        };
    }

    ImportEnding toEnding(ImportEndReason reason) {
        return switch (reason) {
            case IMPORT_END_REASON_CANCELLED -> ImportEnding.CANCELLED;
            case IMPORT_END_REASON_TIMED_OUT -> ImportEnding.TIMED_OUT;
            case IMPORT_END_REASON_INTERRUPTED -> ImportEnding.INTERRUPTED;
            case IMPORT_END_REASON_EXPORT_FAILED -> ImportEnding.EXPORT_FAILED;
            case IMPORT_END_REASON_UPLOAD_FAILED -> ImportEnding.UPLOAD_FAILED;
            case IMPORT_END_REASON_FILE_TOO_LARGE -> ImportEnding.FILE_TOO_LARGE;
            case IMPORT_END_REASON_INVALID_FILE -> ImportEnding.INVALID_FILE;
            case IMPORT_END_REASON_IMPORT_FAILED -> ImportEnding.IMPORT_FAILED;
            case IMPORT_END_REASON_UNSPECIFIED, UNRECOGNIZED -> throw new ValidationException("A reason is required");
        };
    }

    private static ImportJob toJob(MigrationStatus job) {
        ImportJob.Builder builder = ImportJob.newBuilder()
            .setId(job.getId())
            .setStatus(job.getStatus().storedValue())
            .setEndReason(toEndReason(job.getEnding()))
            .setScopedPunishments(toScopedChoice(job.getScopedPunishments()))
            .setIpHistory(toHistoryChoice(job.getIpHistory()));
        if (job.getType() != null) {
            builder.setType(job.getType());
        }
        setIfPresent(job.getRecordsProcessed(), builder::setRecordsProcessed);
        setIfPresent(job.getRecordsSkipped(), builder::setRecordsSkipped);
        setIfPresent(job.getTotalRecords(), builder::setTotalRecords);
        setIfPresent(job.getExportFailed(), builder::setExportFailed);
        setIfPresent(job.getExportSkipped(), builder::setExportSkipped);
        if (job.getError() != null) {
            builder.setError(job.getError());
        }
        if (job.getStartedFrom() != null) {
            builder.setStartedFrom(job.getStartedFrom());
        }
        if (job.getStartedAddress() != null) {
            builder.setStartedAddress(job.getStartedAddress());
        }
        return builder.build();
    }

    private static void setIfPresent(@Nullable Integer value, IntConsumer setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private static ImportEndReason toEndReason(@Nullable ImportEnding ending) {
        return switch (ending) {
            case null -> ImportEndReason.IMPORT_END_REASON_UNSPECIFIED;
            case CANCELLED -> ImportEndReason.IMPORT_END_REASON_CANCELLED;
            case TIMED_OUT -> ImportEndReason.IMPORT_END_REASON_TIMED_OUT;
            case INTERRUPTED -> ImportEndReason.IMPORT_END_REASON_INTERRUPTED;
            case EXPORT_FAILED -> ImportEndReason.IMPORT_END_REASON_EXPORT_FAILED;
            case UPLOAD_FAILED -> ImportEndReason.IMPORT_END_REASON_UPLOAD_FAILED;
            case FILE_TOO_LARGE -> ImportEndReason.IMPORT_END_REASON_FILE_TOO_LARGE;
            case INVALID_FILE -> ImportEndReason.IMPORT_END_REASON_INVALID_FILE;
            case IMPORT_FAILED -> ImportEndReason.IMPORT_END_REASON_IMPORT_FAILED;
        };
    }

    private static ScopedPunishmentChoice toScopedChoice(@Nullable ScopedPunishments choice) {
        return switch (choice) {
            case null -> ScopedPunishmentChoice.SCOPED_PUNISHMENT_CHOICE_UNSPECIFIED;
            case WIDEN -> ScopedPunishmentChoice.SCOPED_PUNISHMENT_CHOICE_WIDEN;
            case SKIP -> ScopedPunishmentChoice.SCOPED_PUNISHMENT_CHOICE_SKIP;
        };
    }

    private static ImportedIpHistoryChoice toHistoryChoice(@Nullable IpHistoryRetention choice) {
        return switch (choice) {
            case null -> ImportedIpHistoryChoice.IMPORTED_IP_HISTORY_CHOICE_UNSPECIFIED;
            case KEEP -> ImportedIpHistoryChoice.IMPORTED_IP_HISTORY_CHOICE_KEEP;
            case STANDARD -> ImportedIpHistoryChoice.IMPORTED_IP_HISTORY_CHOICE_STANDARD;
        };
    }
}
