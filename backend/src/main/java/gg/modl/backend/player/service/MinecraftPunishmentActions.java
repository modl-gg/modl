package gg.modl.backend.player.service;

import gg.modl.backend.player.dto.request.ModifyPunishmentTicketsRequest;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.server.data.Server;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftPunishmentActions {
    private final PunishmentLifecycleService lifecycle;
    private final PunishmentMutationService mutations;
    private final PunishmentEvidenceService evidence;
    private final PunishmentQueryService queries;
    private final MinecraftIssuerAuthorizer issuerAuthorizer;
    private final PunishmentModificationAuthorizer modificationAuthorizer;

    public PunishmentOperationResult pardon(Server server, String punishmentId, @Nullable String issuerName,
                                            MinecraftIssuer issuer, @Nullable String reason, @Nullable String expectedType) {
        authorize(server, issuer, MinecraftModification.PARDON);
        return lifecycle.pardonPunishment(server, punishmentId, issuerName, issuer.staffId(), reason, expectedType);
    }

    public PunishmentOperationResult addNote(Server server, String punishmentId, String note, @Nullable String issuerName,
                                             MinecraftIssuer issuer) {
        authorize(server, issuer, MinecraftModification.NOTE);
        return evidence.addPunishmentNote(server, punishmentId, note, issuerName, issuer.staffId());
    }

    public PunishmentOperationResult addEvidence(Server server, String punishmentId, String evidenceUrl,
                                                 @Nullable String issuerName, MinecraftIssuer issuer) {
        authorize(server, issuer, MinecraftModification.EVIDENCE);
        return evidence.addEvidence(server, punishmentId, evidenceUrl, issuerName, issuer.staffId());
    }

    public Optional<String> createEvidenceUploadToken(Server server, String punishmentId, @Nullable String issuerName,
                                                      MinecraftIssuer issuer) {
        authorize(server, issuer, MinecraftModification.EVIDENCE);
        return queries.createEvidenceUploadToken(server, punishmentId, issuerName);
    }

    public PunishmentOperationResult changeDuration(Server server, String punishmentId, @Nullable Long newDuration,
                                                    @Nullable String issuerName, MinecraftIssuer issuer) {
        authorize(server, issuer, MinecraftModification.DURATION);
        return mutations.changeDuration(server, punishmentId, newDuration, issuerName, issuer.staffId(),
            modificationAuthorizer.shorteningBy(server, issuer));
    }

    public PunishmentOperationResult toggleOption(Server server, String punishmentId, String option, boolean enabled,
                                                  @Nullable String issuerName, MinecraftIssuer issuer) {
        authorize(server, issuer, MinecraftModification.OPTIONS);
        return mutations.toggleOption(server, punishmentId, option, enabled, issuerName, issuer.staffId(),
            modificationAuthorizer.shorteningBy(server, issuer));
    }

    public PunishmentOperationResult modifyTickets(Server server, String punishmentId, @Nullable List<String> addTicketIds,
                                                   @Nullable List<String> removeTicketIds, boolean modifyAssociatedTickets,
                                                   @Nullable String issuerName, MinecraftIssuer issuer) {
        authorize(server, issuer, MinecraftModification.TICKETS);
        return mutations.modifyPunishmentTickets(server, punishmentId, new ModifyPunishmentTicketsRequest(
            addTicketIds, removeTicketIds, modifyAssociatedTickets, issuerName, issuer.staffId()));
    }

    private void authorize(Server server, MinecraftIssuer issuer, MinecraftModification modification) {
        issuerAuthorizer.requirePermission(server, issuer, modification.permission());
    }
}
