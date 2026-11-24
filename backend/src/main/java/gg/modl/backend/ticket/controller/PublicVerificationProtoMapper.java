package gg.modl.backend.ticket.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.stringValue;

import gg.modl.proto.modl.v1.PublicTicketVerificationRequestResponse;
import gg.modl.proto.modl.v1.PublicTicketVerificationResponse;
import gg.modl.proto.modl.v1.TicketVerificationRequiredResponse;
import org.jspecify.annotations.Nullable;

public final class PublicVerificationProtoMapper {
    private PublicVerificationProtoMapper() {
    }

    public static TicketVerificationRequiredResponse toVerificationRequiredResponse(String recordId, @Nullable String emailHint, String message) {
        return TicketVerificationRequiredResponse.newBuilder()
            .setRequiresVerification(true)
            .setEmailHint(stringValue(emailHint))
            .setTicketId(stringValue(recordId))
            .setMessage(stringValue(message))
            .build();
    }

    public static PublicTicketVerificationRequestResponse toRequestVerificationResponse(String emailHint) {
        return PublicTicketVerificationRequestResponse.newBuilder()
            .setSuccess(true)
            .setMessage("Verification code sent")
            .setEmailHint(stringValue(emailHint))
            .build();
    }

    public static PublicTicketVerificationResponse toVerifyResponse(String token) {
        return PublicTicketVerificationResponse.newBuilder()
            .setSuccess(true)
            .setToken(stringValue(token))
            .setMessage("Verification successful")
            .build();
    }
}
