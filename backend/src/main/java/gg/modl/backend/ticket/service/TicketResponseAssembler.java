package gg.modl.backend.ticket.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TicketResponseAssembler {
    private final StaffRepository staffRepository;
    private final TicketPublicLinks ticketPublicLinks;

    public TicketResponse toTicketResponse(Server server, Ticket ticket) {
        return toTicketResponse(ticket, staffAvatars(List.of(ticket)));
    }

    public List<TicketResponse> toTicketResponses(Server server, List<Ticket> tickets) {
        Map<String, String> staffAvatars = staffAvatars(tickets);
        return tickets.stream().map(ticket -> toTicketResponse(ticket, staffAvatars)).toList();
    }

    private static TicketCategory typeOrSupport(Ticket ticket) {
        TicketCategory type = ticket.getType();
        return type != null ? type : TicketCategory.SUPPORT;
    }

    private static TicketStatus statusOrOpen(Ticket ticket) {
        TicketStatus status = ticket.getStatus();
        return status != null ? status : TicketStatus.OPEN;
    }

    private TicketResponse toTicketResponse(Ticket ticket, Map<String, String> staffAvatars) {
        List<TicketReply> processedReplies = processRepliesWithNames(ticket, staffAvatars);
        String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown";

        return new TicketResponse(
            ticket.getId(),
            typeOrSupport(ticket).getId(),
            typeOrSupport(ticket).getDisplayName(),
            ticket.getSubject() != null ? ticket.getSubject() : "No Subject",
            statusOrOpen(ticket).getId(),
            appealWorkflowStatusId(ticket),
            creatorName,
            ticket.getCreatorUuid(),
            creatorName,
            ticket.getReportedPlayer(),
            ticket.getReportedPlayerUuid(),
            ticket.getCreated(),
            ticket.isLocked(),
            processedReplies,
            ticket.getNotes(),
            ticket.getTags(),
            ticket.getFormData(),
            ticket.getData(),
            ticket.getChatMessages(),
            ticket.getAiAnalysis(),
            ticket.isEmailAuthEnabled(),
            ticket.isHidden(),
            ticketPublicLinks.replay(ticket),
            ticket.getAssignedTo(),
            ticketPublicLinks.of(ticket)
        );
    }

    @Nullable
    private static String appealWorkflowStatusId(Ticket ticket) {
        if (ticket.getAppealWorkflowStatus() != null) {
            return ticket.getAppealWorkflowStatus().getId();
        }
        return ticket.getType() == TicketCategory.APPEAL ? AppealWorkflowStatus.OPEN.getId() : null;
    }

    private Map<String, String> staffAvatars(List<Ticket> tickets) {
        Set<String> staffUsernames = tickets.stream()
            .flatMap(ticket -> ticket.getReplies() == null ? Stream.empty() : ticket.getReplies().stream())
            .filter(reply -> reply.isStaff() && (reply.getAvatar() == null || reply.getAvatar().isBlank()))
            .map(TicketReply::getName)
            .filter(name -> name != null && !name.isBlank())
            .collect(Collectors.toSet());
        if (staffUsernames.isEmpty()) {
            return Map.of();
        }
        return staffRepository.findByUsernames(staffUsernames).stream()
            .filter(staff -> staff.getAssignedMinecraftUuid() != null && !staff.getAssignedMinecraftUuid().isBlank())
            .collect(Collectors.toMap(
                Staff::getUsername,
                staff -> String.format("https://mc-heads.net/avatar/%s/32", staff.getAssignedMinecraftUuid()),
                (first, second) -> first));
    }

    private List<TicketReply> processRepliesWithNames(Ticket ticket, Map<String, String> staffAvatars) {
        if (ticket.getReplies() == null || ticket.getReplies().isEmpty()) {
            return ticket.getReplies();
        }

        String creatorName = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player";
        return ticket.getReplies()
            .stream().map(reply -> {
                String name = reply.getName();
                if (name == null || name.isBlank()) {
                    name = reply.isStaff() ? "Staff" : creatorName;
                }
                String type = reply.getType();
                if (type == null || type.isBlank()) {
                    type = reply.isStaff() ? "staff" : "user";
                }
                String avatar = reply.getAvatar();
                if (reply.isStaff() && (avatar == null || avatar.isBlank()) && name != null) {
                    String staffAvatar = staffAvatars.get(name);
                    if (staffAvatar != null) {
                        avatar = staffAvatar;
                    }
                }
                return reply.toBuilder().name(name).type(type).avatar(avatar).build();
            }).toList();
    }
}
