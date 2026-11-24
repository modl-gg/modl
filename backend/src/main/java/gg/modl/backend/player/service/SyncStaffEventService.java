package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SyncStaffEventService {
    private static final int TICKET_EVENT_LIMIT = 20;
    private static final int PUNISHMENT_EVENT_LIMIT = 50;

    private final TicketRepository ticketRepository;
    private final PunishmentRepository punishmentRepository;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffNotices staffNotices;

    public List<Map<String, Object>> collectStaffEvents(
        Server server,
        Instant lastSync,
        PunishmentTypeLookup types,
        List<SyncPunishmentEntry> recentlyModifiedPunishments
    ) {
        List<Map<String, Object>> notifications = new ArrayList<>();
        collectTicketNotifications(lastSync, notifications);
        collectPunishmentNotifications(server, lastSync, types, notifications);
        collectPardonNotifications(lastSync, recentlyModifiedPunishments, notifications);
        return notifications;
    }

    private void collectTicketNotifications(Instant lastSync, List<Map<String, Object>> notifications) {
        try {
            List<Ticket> recentTickets = ticketRepository.findCreatedAfterExcludingUnfinished(lastSync, TICKET_EVENT_LIMIT);
            Map<String, TicketReply> firstReplies = ticketRepository.firstReplies(
                recentTickets.stream().map(Ticket::getId).toList());
            for (Ticket ticket : recentTickets) {
                staffNotices.ticketCreated(ticket, firstReplies.get(ticket.getId())).ifPresent(notifications::add);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to collect ticket notifications during sync", e);
        }
    }

    private void collectPunishmentNotifications(Server server, Instant lastSync, PunishmentTypeLookup types,
                                                List<Map<String, Object>> notifications) {
        try {
            List<PunishmentRecord> issued = punishmentRepository.findRecent(lastSync, PUNISHMENT_EVENT_LIMIT).stream()
                .filter(found -> found.punishment().getIssued().isAfter(lastSync))
                .toList();
            if (issued.isEmpty()) {
                return;
            }
            Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPunishments(server,
                issued.stream().map(PunishmentRecord::punishment).toList());
            for (PunishmentRecord found : issued) {
                notifications.add(staffNotices.punishmentIssued(found, types, resolvedIssuers));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to collect punishment notifications during sync", e);
        }
    }

    private void collectPardonNotifications(Instant lastSync, List<SyncPunishmentEntry> recentlyModifiedPunishments,
                                            List<Map<String, Object>> notifications) {
        long since = lastSync.toEpochMilli();
        for (SyncPunishmentEntry modified : recentlyModifiedPunishments) {
            for (SimplePunishmentView.Modification modification : modified.punishment().modifications()) {
                Long at = modification.timestamp();
                if (PunishmentModificationType.isPardon(modification.type()) && at != null && at > since) {
                    notifications.add(staffNotices.punishmentPardoned(modified, modification));
                }
            }
        }
    }
}
