package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.ActionIssuer;
import gg.modl.minecraft.api.http.request.AddPunishmentEvidenceRequest;
import gg.modl.minecraft.api.http.request.AddPunishmentNoteRequest;
import gg.modl.minecraft.api.http.request.ChangePunishmentDurationRequest;
import gg.modl.minecraft.api.http.request.ModifyPunishmentTicketsRequest;
import gg.modl.minecraft.api.http.request.PardonPlayerRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.api.http.request.PunishmentAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.PunishmentCreateRequest;
import gg.modl.minecraft.api.http.request.StatWipeAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.TogglePunishmentOptionRequest;
import gg.modl.minecraft.api.http.response.EvidenceUploadTokenResponse;
import gg.modl.minecraft.api.http.response.PaginatedPunishmentsResponse;
import gg.modl.minecraft.api.http.response.PardonResponse;
import gg.modl.minecraft.api.http.response.PunishmentCreateResponse;
import gg.modl.minecraft.api.http.response.PunishmentDetailResponse;
import gg.modl.minecraft.api.http.response.PunishmentPreviewResponse;
import gg.modl.minecraft.api.http.response.PunishmentTypesResponse;
import gg.modl.minecraft.api.http.response.RecentPunishmentsResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PunishmentApi {

    CompletableFuture<PunishmentCreateResponse> createPunishmentWithResponse(PunishmentCreateRequest request);

    CompletableFuture<Void> acknowledgePunishment(PunishmentAcknowledgeRequest request);

    CompletableFuture<PunishmentTypesResponse> getPunishmentTypes();

    CompletableFuture<PardonResponse> pardonPunishment(PardonPunishmentRequest request);

    CompletableFuture<PardonResponse> pardonPlayer(PardonPlayerRequest request);

    CompletableFuture<RecentPunishmentsResponse> getRecentPunishments(int hours);

    CompletableFuture<PunishmentPreviewResponse> getPunishmentPreview(UUID playerUuid, int typeOrdinal);

    CompletableFuture<Void> addPunishmentNote(AddPunishmentNoteRequest request);

    CompletableFuture<Void> addPunishmentEvidence(AddPunishmentEvidenceRequest request);

    CompletableFuture<Void> changePunishmentDuration(ChangePunishmentDurationRequest request);

    CompletableFuture<Void> togglePunishmentOption(TogglePunishmentOptionRequest request);

    CompletableFuture<PunishmentDetailResponse> getPunishmentDetail(String punishmentId);

    CompletableFuture<EvidenceUploadTokenResponse> createEvidenceUploadToken(String punishmentId, String issuerName,
                                                                                    ActionIssuer issuer);

    CompletableFuture<Void> modifyPunishmentTickets(ModifyPunishmentTicketsRequest request);

    CompletableFuture<PaginatedPunishmentsResponse> getPlayerPunishments(UUID uuid, int page, int limit);

    CompletableFuture<Void> acknowledgeStatWipe(StatWipeAcknowledgeRequest request);
}
