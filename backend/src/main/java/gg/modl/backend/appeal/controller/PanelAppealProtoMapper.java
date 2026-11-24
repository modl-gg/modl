package gg.modl.backend.appeal.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.addAll;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.structToMap;
import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.valuesToObjects;

import gg.modl.backend.appeal.dto.request.AddAppealReplyRequest;
import gg.modl.backend.appeal.dto.request.CreateAppealRequest;
import gg.modl.backend.appeal.dto.request.UpdateAppealStatusRequest;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.ticket.controller.PanelTicketProtoMapper;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.proto.modl.v1.AddTicketReplyResponse;
import gg.modl.proto.modl.v1.AppealTicketsResponse;
import java.util.List;
import java.util.Map;

final class PanelAppealProtoMapper {
    private PanelAppealProtoMapper() {
    }

    static AppealTicketsResponse toAppealTicketsResponse(List<TicketResponse> appeals) {
        AppealTicketsResponse.Builder builder = AppealTicketsResponse.newBuilder();
        addAll(appeals, PanelTicketProtoMapper::toTicketResponse, builder::addTickets);
        return builder.build();
    }

    static gg.modl.proto.modl.v1.TicketResponse toTicketResponse(TicketResponse appeal) {
        return PanelTicketProtoMapper.toTicketResponse(appeal);
    }

    static AddTicketReplyResponse toAddReplyResponse(TicketReply reply) {
        return AddTicketReplyResponse.newBuilder()
            .setSuccess(true)
            .setReply(PanelTicketProtoMapper.toPublicTicketReply(reply))
            .build();
    }

    static CreateAppealRequest fromCreateAppealRequest(
        gg.modl.proto.modl.v1.CreateAppealRequest request
    ) {
        return new CreateAppealRequest(
            request.getPunishmentId(),
            request.getPlayerUuid(),
            request.getEmail(),
            request.hasReason() ? request.getReason() : null,
            request.hasEvidence() ? request.getEvidence() : null,
            request.hasAdditionalData() ? structToMap(request.getAdditionalData()) : null,
            valuesToObjects(request.getAttachmentsList()),
            request.getFieldLabelsMap().isEmpty() ? null : Map.copyOf(request.getFieldLabelsMap())
        );
    }

    static AddAppealReplyRequest fromAddAppealReplyRequest(gg.modl.proto.modl.v1.AddAppealReplyRequest request,
                                                           String staffName) {
        requireWithinLimit("name", request.getName(), RequestValidationLimits.APPEAL_REPLY_NAME_MAX_LENGTH);
        requireWithinLimit("action", request.getAction(), RequestValidationLimits.APPEAL_REPLY_ACTION_MAX_LENGTH);
        return new AddAppealReplyRequest(
            staffName,
            request.getContent(),
            request.hasAction() ? request.getAction() : null,
            valuesToObjects(request.getAttachmentsList())
        );
    }

    static UpdateAppealStatusRequest fromUpdateAppealStatusRequest(gg.modl.proto.modl.v1.UpdateAppealStatusRequest request,
                                                                   String staffUsername) {
        return new UpdateAppealStatusRequest(
            request.getStatus().isBlank() ? null : request.getStatus(),
            request.hasLocked() ? request.getLocked() : null,
            staffUsername,
            request.hasResolution() ? request.getResolution() : null
        );
    }

    private static void requireWithinLimit(String field, String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new ValidationException(field + " exceeds maximum length of " + maxLength);
        }
    }

}
