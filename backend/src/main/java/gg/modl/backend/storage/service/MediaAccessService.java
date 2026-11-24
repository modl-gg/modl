package gg.modl.backend.storage.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.TicketService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MediaAccessService {
    private final TicketService ticketService;
    private final PublicRecordAccessService publicRecordAccessService;

    public AccessResult validatePublicUploadAccess(
        Server server,
        String uploadType,
        @Nullable String entityId,
        @Nullable String accessToken
    ) {
        if (entityId == null || entityId.isBlank()) {
            return AccessResult.denied("entityId is required for public uploads");
        }

        String normalizedEntityId = entityId.trim();
        String normalizedType = StorageKeyUtils.normalizeUploadType(uploadType);

        if (TempUploadKeys.isAnonymousTempEntity(normalizedEntityId)) {
            if ("ticket".equals(normalizedType) || "appeal".equals(normalizedType)) {
                return AccessResult.allowed();
            }
            return AccessResult.denied("Temporary uploads are only allowed for ticket and appeal types");
        }

        Optional<Ticket> found = ticketService.getTicketRaw(server, normalizedEntityId);
        if (found.isEmpty()) {
            return AccessResult.notFound();
        }
        Ticket ticket = found.get();
        PublicRecordAccessService.AccessResult access = publicRecordAccessService.authorize(server, ticket, accessToken);
        if (access.access() == PublicRecordAccessService.Access.NOT_FOUND) {
            return AccessResult.notFound();
        }
        if (access.access() == PublicRecordAccessService.Access.TOKEN_REQUIRED) {
            return AccessResult.denied(access.accessRequiredMessage());
        }

        boolean isAppealTicket = ticket.getType() == TicketCategory.APPEAL;
        if ("appeal".equals(normalizedType) && !isAppealTicket) {
            return AccessResult.denied("Entity is not an appeal ticket");
        }
        if ("ticket".equals(normalizedType) && isAppealTicket) {
            return AccessResult.denied("Appeal uploads must use uploadType=appeal");
        }
        return AccessResult.allowed();
    }

    public enum AccessStatus {
        ALLOWED,
        DENIED,
        NOT_FOUND
    }

    public record AccessResult(AccessStatus status, @Nullable String error) {
        static AccessResult allowed() {
            return new AccessResult(AccessStatus.ALLOWED, null);
        }

        static AccessResult denied(String error) {
            return new AccessResult(AccessStatus.DENIED, error);
        }

        static AccessResult notFound() {
            return new AccessResult(AccessStatus.NOT_FOUND, null);
        }

        public boolean isAllowed() {
            return status == AccessStatus.ALLOWED;
        }

        public String requireError() {
            if (error == null) {
                throw new IllegalStateException("Only a denied upload access carries an error, this one is " + status);
            }
            return error;
        }
    }
}
