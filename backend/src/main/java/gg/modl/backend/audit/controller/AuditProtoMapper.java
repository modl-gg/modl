package gg.modl.backend.audit.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.stringValue;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toStruct;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.audit.dto.response.AuditPunishmentResponse;
import gg.modl.backend.audit.dto.response.AuditPunishmentTypeResponse;
import gg.modl.backend.audit.dto.response.DatabaseTablePage;
import gg.modl.backend.audit.dto.response.PunishmentAuditPage;
import gg.modl.backend.audit.dto.response.StaffDetailsResponse;
import gg.modl.backend.audit.dto.response.StaffPerformanceResponse;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.proto.modl.v1.AuditBulkOperationResponse;
import gg.modl.proto.modl.v1.AuditDatabaseTableResponse;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.proto.modl.v1.AuditPunishment;
import gg.modl.proto.modl.v1.AuditPunishmentPhase;
import gg.modl.proto.modl.v1.AuditPunishmentTypesResponse;
import gg.modl.proto.modl.v1.AuditPunishmentsResponse;
import gg.modl.proto.modl.v1.AuditRollbackResponse;
import com.google.protobuf.Timestamp;
import gg.modl.proto.modl.v1.StaffPerformanceListResponse;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

final class AuditProtoMapper {

    private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;

    private AuditProtoMapper() {
    }

    @Nullable
    static Instant toDate(Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }
        if (timestamp.getSeconds() < MIN_TIMESTAMP_SECONDS || timestamp.getSeconds() > MAX_TIMESTAMP_SECONDS) {
            throw new ValidationException("The date is out of range");
        }
        return Instant.ofEpochMilli(timestamp.getSeconds() * 1000L + timestamp.getNanos() / 1_000_000L);
    }

    private static void setTimestamp(Consumer<Timestamp> setter, @Nullable Instant date) {
        if (date != null) {
            setter.accept(toTimestamp(date));
        }
    }

    static StaffPerformanceListResponse toStaffPerformanceList(List<StaffPerformanceResponse> performance) {
        StaffPerformanceListResponse.Builder builder = StaffPerformanceListResponse.newBuilder();
        performance.forEach(item -> builder.addStaff(toStaffPerformance(item)));
        return builder.build();
    }

    static gg.modl.proto.modl.v1.StaffDetailsResponse toStaffDetails(StaffDetailsResponse details) {
        gg.modl.proto.modl.v1.StaffDetailsResponse.Builder builder =
            gg.modl.proto.modl.v1.StaffDetailsResponse.newBuilder()
                .setUsername(stringValue(details.username()))
                .setPeriod(stringValue(details.period()));
        details.punishments().forEach(punishment -> {
            gg.modl.proto.modl.v1.StaffDetailsResponse.PunishmentDetail.Builder detail =
                gg.modl.proto.modl.v1.StaffDetailsResponse.PunishmentDetail.newBuilder()
                    .setId(stringValue(punishment.id()))
                    .setPlayerId(stringValue(punishment.playerId()))
                    .setPlayerName(stringValue(punishment.playerName()))
                    .setType(stringValue(punishment.type()))
                    .setReason(stringValue(punishment.reason()))
                    .setDuration(stringValue(punishment.duration()))
                    .setPhase(toProto(punishment.phase()))
                    .setRolledBack(punishment.rolledBack());
            setTimestamp(detail::setIssued, punishment.issued());
            if (punishment.effectiveCategory() != null) {
                detail.setEffectiveCategory(punishment.effectiveCategory());
            }
            builder.addPunishments(detail.build());
        });
        details.tickets().forEach(ticket -> {
            gg.modl.proto.modl.v1.StaffDetailsResponse.TicketDetail.Builder detail =
                gg.modl.proto.modl.v1.StaffDetailsResponse.TicketDetail.newBuilder()
                    .setId(stringValue(ticket.id()))
                    .setSubject(stringValue(ticket.subject()))
                    .setCategory(stringValue(ticket.category()))
                    .setStatus(stringValue(ticket.status()))
                    .setResponseTime(ticket.responseTime());
            setTimestamp(detail::setLastActivity, ticket.lastActivity());
            builder.addTickets(detail.build());
        });
        details.dailyActivity().forEach(activity -> builder.addDailyActivity(
            gg.modl.proto.modl.v1.StaffDetailsResponse.DailyActivity.newBuilder()
                .setDate(stringValue(activity.date()))
                .setPunishments(activity.punishments())
                .setTickets(activity.tickets())
                .build()));
        details.punishmentTypeBreakdown().forEach(breakdown -> builder.addPunishmentTypeBreakdown(
            gg.modl.proto.modl.v1.StaffDetailsResponse.PunishmentTypeBreakdown.newBuilder()
                .setType(stringValue(breakdown.type()))
                .setCount(breakdown.count())
                .build()));
        StaffDetailsResponse.Summary summary = details.summary();
        builder.setSummary(gg.modl.proto.modl.v1.StaffDetailsResponse.Summary.newBuilder()
            .setTotalPunishments(summary.totalPunishments())
            .setTotalTickets(summary.totalTickets())
            .setAvgResponseTime(summary.avgResponseTime())
            .setEvidenceUploads(summary.evidenceUploads())
            .build());
        return builder.build();
    }

    static AuditPunishmentsResponse toPunishments(PunishmentAuditPage page) {
        AuditPunishmentsResponse.Builder builder = AuditPunishmentsResponse.newBuilder()
            .setHasMore(page.hasMore());
        if (page.nextCursor() != null) {
            builder.setNextCursor(page.nextCursor());
        }
        page.punishments().forEach(punishment -> builder.addPunishments(toPunishment(punishment)));
        return builder.build();
    }

    static AuditPunishmentTypesResponse toPunishmentTypes(List<AuditPunishmentTypeResponse> types) {
        AuditPunishmentTypesResponse.Builder builder = AuditPunishmentTypesResponse.newBuilder();
        types.forEach(type -> builder.addTypes(AuditPunishmentTypesResponse.TypeEntry.newBuilder()
            .setOrdinal(type.ordinal())
            .setName(stringValue(type.name()))
            .setActiveCount(Math.toIntExact(type.activeCount()))
            .setUnstartedCount(Math.toIntExact(type.unstartedCount()))
            .build()));
        return builder.build();
    }

    static AuditRollbackResponse toRollbackResponse(boolean success, String message) {
        return AuditRollbackResponse.newBuilder()
            .setSuccess(success)
            .setMessage(stringValue(message))
            .build();
    }

    static AuditBulkOperationResponse toBulkOperationResponse(boolean success, int count, String message) {
        return AuditBulkOperationResponse.newBuilder()
            .setSuccess(success)
            .setCount(count)
            .setMessage(stringValue(message))
            .build();
    }

    static AuditDatabaseTableResponse toDatabaseTableResponse(DatabaseTablePage page) {
        AuditDatabaseTableResponse.Builder builder = AuditDatabaseTableResponse.newBuilder()
            .setTotal(page.total())
            .setLimit(page.limit())
            .setSkip(page.skip());
        page.rows().forEach(row -> builder.addData(toStruct(row)));
        return builder.build();
    }

    private static gg.modl.proto.modl.v1.StaffPerformanceResponse toStaffPerformance(StaffPerformanceResponse item) {
        gg.modl.proto.modl.v1.StaffPerformanceResponse.Builder builder =
            gg.modl.proto.modl.v1.StaffPerformanceResponse.newBuilder()
                .setId(stringValue(item.id()))
                .setUsername(stringValue(item.username()))
                .setRole(stringValue(item.role()))
                .setTotalActions(item.totalActions())
                .setTicketResponses(item.ticketResponses())
                .setPunishmentsIssued(item.punishmentsIssued())
                .setAvgResponseTime(item.avgResponseTime());
        setTimestamp(builder::setLastActive, item.lastActive());
        return builder.build();
    }

    private static AuditPunishmentPhase toProto(PunishmentPhase phase) {
        return switch (phase) {
            case ACTIVE -> AuditPunishmentPhase.AUDIT_PUNISHMENT_PHASE_ACTIVE;
            case UNSTARTED -> AuditPunishmentPhase.AUDIT_PUNISHMENT_PHASE_UNSTARTED;
            case ENDED -> AuditPunishmentPhase.AUDIT_PUNISHMENT_PHASE_ENDED;
        };
    }

    private static AuditPunishment toPunishment(AuditPunishmentResponse punishment) {
        AuditPunishment.Builder builder = AuditPunishment.newBuilder()
                .setId(stringValue(punishment.id()))
                .setPlayerId(stringValue(punishment.playerId()))
                .setPlayerName(stringValue(punishment.playerName()))
                .setType(stringValue(punishment.type()))
                .setTypeOrdinal(punishment.typeOrdinal())
                .setCategory(stringValue(punishment.category()))
                .setStaffName(stringValue(punishment.staffName()))
                .setReason(stringValue(punishment.reason()))
                .setPhase(toProto(punishment.phase()))
                .setHasEvidence(punishment.hasEvidence())
                .setEvidenceCount15(punishment.evidenceCount());
        setTimestamp(builder::setIssued, punishment.issued());
        setTimestamp(builder::setStarted, punishment.started());
        setTimestamp(builder::setExpires, punishment.expires());
        if (punishment.duration() != null) {
            builder.setDuration(punishment.duration());
        }
        if (punishment.evidence() != null) {
            punishment.evidence().forEach(evidence -> builder.addEvidence16(
                AuditPunishment.EvidenceItem.newBuilder()
                    .setText(stringValue(evidence.text()))
                    .setUrl(stringValue(evidence.url()))
                    .setType(stringValue(evidence.type()))
                    .setFileName(stringValue(evidence.fileName()))
                    .build()));
        }
        if (punishment.attachedTicketIds() != null) {
            builder.addAllAttachedTicketIds(punishment.attachedTicketIds());
        }
        if (punishment.effectiveCategory() != null) {
            builder.setEffectiveCategory(punishment.effectiveCategory());
        }
        return builder.build();
    }
}
