package gg.modl.backend.status.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.SupportReport;
import gg.modl.proto.modl.v1.StatusEntry;
import gg.modl.proto.modl.v1.StatusLevel;
import gg.modl.proto.modl.v1.StatusReportResponse;

final class StatusProtoMapper {
    private StatusProtoMapper() {
    }

    static StatusReportResponse toResponse(SupportReport report) {
        StatusReportResponse.Builder response = StatusReportResponse.newBuilder()
            .setReport(report.render())
            .setGeneratedAt(toTimestamp(report.generatedAt()));
        report.facts().forEach(fact -> response.addEntries(toEntry(fact)));
        return response.build();
    }

    private static StatusEntry toEntry(StatusFact fact) {
        return StatusEntry.newBuilder()
            .setKey(fact.item().key())
            .setSection(fact.item().section().title())
            .setLabel(fact.item().label())
            .setValue(fact.value())
            .setLevel(toLevel(fact.level()))
            .build();
    }

    private static StatusLevel toLevel(gg.modl.backend.status.StatusLevel level) {
        return switch (level) {
            case OK -> StatusLevel.STATUS_LEVEL_OK;
            case WARNING -> StatusLevel.STATUS_LEVEL_WARNING;
            case PROBLEM -> StatusLevel.STATUS_LEVEL_PROBLEM;
        };
    }
}
