package gg.modl.backend.ticket.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.repository.TicketSubscription;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.dto.response.SubscriptionUpdateResponse;
import gg.modl.backend.ticket.dto.response.TicketSubscriptionResponse;
import gg.modl.backend.ticket.repository.ReplyStamp;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketSubscriptionService {
    private static final int MAX_UPDATES_LIMIT = 25;
    private static final int MAX_TICKETS_TO_SCAN = 250;
    private static final String UPDATE_ID_SEPARATOR = "::";
    private static final Duration ASSIGNED_NO_CURSOR_WINDOW = Duration.ofDays(7);
    private static final Comparator<ReplyStamp> NEWEST_STAMP_LAST =
        Comparator.comparing(ReplyStamp::created).thenComparingInt(ReplyStamp::seq);

    private final StaffRepository staffRepository;
    private final TicketRepository ticketRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<TicketSubscriptionResponse> getSubscriptions(Server server, String staffEmail) {
        List<TicketSubscription> subscriptions = staffRepository.findSubscriptions(staffEmail);
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        Map<String, TicketSummary> ticketsById = ticketRepository
            .findSummaries(subscriptions.stream().map(TicketSubscription::ticketId).toList())
            .stream()
            .collect(Collectors.toMap(TicketSummary::getId, Function.identity()));

        List<TicketSubscriptionResponse> responses = new ArrayList<>();
        for (TicketSubscription subscription : subscriptions) {
            TicketSummary ticket = ticketsById.get(subscription.ticketId());
            if (ticket != null) {
                responses.add(new TicketSubscriptionResponse(
                    subscription.ticketId(),
                    buildTicketTitle(ticket.getId(), ticket.getSubject()),
                    subscription.subscribedAt()
                ));
            }
        }
        return responses;
    }

    public boolean unsubscribe(Server server, String staffEmail, String ticketId) {
        return unitOfWork.write(() -> staffRepository.unsubscribe(staffEmail, ticketId));
    }

    public List<SubscriptionUpdateResponse> getUpdates(Server server, String staffEmail, int limit) {
        int safeLimit = clampLimit(limit);
        Map<String, TicketSubscription> subscriptions = staffRepository.findSubscriptions(staffEmail).stream()
            .collect(Collectors.toMap(TicketSubscription::ticketId, Function.identity()));
        if (subscriptions.isEmpty()) {
            return List.of();
        }

        List<TicketSummary> tickets = ticketRepository.findRecentActiveWithRepliesByIds(subscriptions.keySet(), ticketScanLimit(safeLimit));
        Map<String, Instant> unreadAfter = new LinkedHashMap<>();
        for (TicketSummary ticket : tickets) {
            TicketSubscription subscription = subscriptions.get(ticket.getId());
            if (subscription != null) {
                unreadAfter.put(ticket.getId(), unreadFloor(subscription));
            }
        }
        return unreadUpdates(tickets, unreadAfter, reply -> true, safeLimit);
    }

    private static Instant unreadFloor(TicketSubscription subscription) {
        Instant lastRead = subscription.lastReadAt();
        return lastRead != null && lastRead.isAfter(subscription.subscribedAt()) ? lastRead : subscription.subscribedAt();
    }

    private List<SubscriptionUpdateResponse> unreadUpdates(List<TicketSummary> tickets, Map<String, Instant> unreadAfter,
                                                           Predicate<ReplyStamp> counted, int limit) {
        if (unreadAfter.isEmpty()) {
            return List.of();
        }
        Instant earliest = Collections.min(unreadAfter.values());
        Map<String, List<ReplyStamp>> unread = ticketRepository.findReplyStamps(unreadAfter.keySet(), earliest).stream()
            .filter(reply -> isUnread(reply, unreadAfter.get(reply.ticketId())))
            .filter(counted)
            .collect(Collectors.groupingBy(ReplyStamp::ticketId));
        List<UnreadTicket> updated = tickets.stream()
            .flatMap(ticket -> UnreadTicket.of(ticket, unread.get(ticket.getId())).stream())
            .sorted(Comparator.comparing(UnreadTicket::newest, NEWEST_STAMP_LAST).reversed())
            .limit(limit)
            .toList();
        Map<String, Integer> latestSeq = updated.stream()
            .collect(Collectors.toMap(UnreadTicket::id, unreadTicket -> unreadTicket.newest().seq()));
        Map<String, TicketReply> latest = ticketRepository.findReplies(latestSeq);
        List<SubscriptionUpdateResponse> updates = new ArrayList<>();
        for (UnreadTicket unreadTicket : updated) {
            TicketReply latestReply = latest.get(unreadTicket.id());
            if (latestReply != null) {
                updates.add(toSubscriptionUpdate(unreadTicket.ticket(), latestReply, unreadTicket.unreadReplies()));
            }
        }
        return updates;
    }

    private static boolean isUnread(ReplyStamp reply, @Nullable Instant unreadAfter) {
        return unreadAfter != null && reply.created().isAfter(unreadAfter);
    }

    private record UnreadTicket(TicketSummary ticket, int unreadReplies, ReplyStamp newest) {
        static Optional<UnreadTicket> of(TicketSummary ticket, @Nullable List<ReplyStamp> unread) {
            if (unread == null || unread.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new UnreadTicket(ticket, unread.size(), Collections.max(unread, NEWEST_STAMP_LAST)));
        }

        String id() {
            return ticket.getId();
        }
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_UPDATES_LIMIT));
    }

    private static int ticketScanLimit(int limit) {
        return Math.min(MAX_TICKETS_TO_SCAN, Math.max(50, limit * 10));
    }

    public boolean markAsRead(Server server, String staffEmail, String updateId) {
        return subscribeAndMarkRead(updateId.split(UPDATE_ID_SEPARATOR)[0], staffEmail);
    }

    public void markTicketAsRead(Server server, String ticketId, String staffEmail) {
        subscribeAndMarkRead(ticketId, staffEmail);
    }

    public void ensureSubscription(Server server, String ticketId, String staffEmail) {
        unitOfWork.write(() -> {
            findTicketId(ticketId).ifPresent(id -> staffRepository.subscribe(staffEmail, id, clock.instant()));
        });
    }

    private boolean subscribeAndMarkRead(String ticketId, String staffEmail) {
        return unitOfWork.write(() -> findTicketId(ticketId)
            .map(id -> {
                Instant now = clock.instant();
                staffRepository.subscribe(staffEmail, id, now);
                return staffRepository.markSubscriptionRead(staffEmail, id, now);
            })
            .orElse(false));
    }

    private Optional<String> findTicketId(String ticketId) {
        return ticketRepository.findSummaries(List.of(ticketId)).stream().findFirst().map(TicketSummary::getId);
    }

    public List<SubscriptionUpdateResponse> getAssignedTicketUpdates(Server server, String staffEmail, int limit) {
        int safeLimit = clampLimit(limit);
        Staff staff = staffRepository.findByEmail(staffEmail).orElse(null);
        if (staff == null) {
            return List.of();
        }
        String rawIdentifier = staff.getUsername() != null ? staff.getUsername() : staffEmail.split("@")[0];
        String staffIdentifier = TicketAssigneeUtil.normalizeSingle(rawIdentifier);
        if (staffIdentifier == null) {
            return List.of();
        }

        List<TicketSummary> tickets = ticketRepository.findRecentAssignedWithReplies(staffIdentifier, ticketScanLimit(safeLimit));
        Map<String, Instant> lastSeen = staffRepository.findSubscriptions(staffEmail).stream()
            .filter(subscription -> subscription.lastReadAt() != null)
            .collect(Collectors.toMap(TicketSubscription::ticketId, TicketSubscription::lastReadAt));
        Instant windowStart = clock.instant().minus(ASSIGNED_NO_CURSOR_WINDOW);
        Map<String, Instant> unreadAfter = new LinkedHashMap<>();
        tickets.forEach(ticket -> unreadAfter.put(ticket.getId(), lastSeen.getOrDefault(ticket.getId(), windowStart)));
        Predicate<ReplyStamp> notOwnReply = reply -> !reply.staff()
            || !staffIdentifier.equals(TicketAssigneeUtil.normalizeSingle(reply.authorName()));
        return unreadUpdates(tickets, unreadAfter, notOwnReply, safeLimit);
    }

    private SubscriptionUpdateResponse toSubscriptionUpdate(TicketSummary ticket, TicketReply latestReply, int unreadReplies) {
        return new SubscriptionUpdateResponse(
            ticket.getId() + UPDATE_ID_SEPARATOR + latestReply.getId(),
            ticket.getId(),
            buildTicketTitle(ticket.getId(), ticket.getSubject()),
            latestReply.getContent(),
            latestReply.getName(),
            latestReply.getCreated(),
            latestReply.isStaff(),
            false,
            unreadReplies > 1 ? unreadReplies - 1 : null
        );
    }

    private static String buildTicketTitle(String id, @Nullable String subject) {
        return id + ": " + (subject != null ? subject : "Untitled Ticket");
    }
}
