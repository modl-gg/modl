package gg.modl.backend.player.service;

import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaffNotices {
    private static final String UNKNOWN = "Unknown";
    private static final String CREATED_SERVER = "createdServer";

    private final Clock clock;
    private final PublicUrls publicUrls;
    private final PlayerStatusCalculator statusCalculator;

    public Optional<Map<String, Object>> ticketCreated(Ticket ticket, @Nullable TicketReply opening) {
        TicketCategory category = ticket.getType();
        if (category == TicketCategory.APPLICATION) {
            return Optional.empty();
        }
        String creatorName = Objects.requireNonNullElse(ticket.getCreatorName(), UNKNOWN);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("creatorName", creatorName);
        data.put("subject", Objects.requireNonNullElse(ticket.getSubject(), ""));
        data.put("firstReplyContent", openingText(opening));
        data.put("ticketUrl", publicUrls.staffTicket(ticket.getId()));
        data.put("ticketType", category.getId());
        if (category.isReport()) {
            data.put("reportedPlayer", Objects.requireNonNullElse(ticket.getReportedPlayer(), ""));
            data.put("category", category.getId());
        }

        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("id", "ticket_" + ticket.getId());
        notice.put("type", StaffNoticeType.TICKET_CREATED.name());
        notice.put("message", ticketMessage(ticket, creatorName));
        notice.put("timestamp", ticket.getCreated().toEpochMilli());
        notice.put("data", data);
        return Optional.of(notice);
    }

    public Map<String, Object> punishmentIssued(PunishmentRecord issued, PunishmentTypeLookup types,
                                                Map<String, String> resolvedIssuers) {
        Punishment punishment = issued.punishment();
        String issuerName = PunishmentMapper.resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers);
        return Map.of(
            "id", "punishment_" + punishment.getId(),
            "type", StaffNoticeType.PUNISHMENT_ISSUED.name(),
            "message", issuerName + ": " + issuedAction(punishment, types.live(punishment.getTypeOrdinal())) + " "
                + issued.playerUsername() + " (" + types.name(punishment.getTypeOrdinal()) + ")",
            "timestamp", punishment.getIssued().toEpochMilli()
        );
    }

    public Optional<Map<String, Object>> latestPardon(SyncPunishmentEntry modified) {
        List<SimplePunishmentView.Modification> modifications = modified.punishment().modifications();
        if (modifications.isEmpty()) {
            return Optional.empty();
        }
        SimplePunishmentView.Modification latest = modifications.getLast();
        return PunishmentModificationType.isPardon(latest.type())
            ? Optional.of(punishmentPardoned(modified, latest))
            : Optional.empty();
    }

    public Map<String, Object> punishmentPardoned(SyncPunishmentEntry pardoned, SimplePunishmentView.Modification pardon) {
        SimplePunishmentView punishment = pardoned.punishment();
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("id", "pardon_" + punishment.id());
        notice.put("type", StaffNoticeType.PUNISHMENT_PARDONED.name());
        notice.put("message", pardon.issuerName() + ": pardoned " + pardoned.username() + "'s " + punishment.type());
        notice.put("timestamp", Objects.requireNonNullElseGet(pardon.timestamp(), clock::millis));
        return notice;
    }

    private String issuedAction(Punishment punishment, @Nullable PunishmentType type) {
        if (type != null && type.isKick()) {
            return "kicked";
        }
        ResolvedCategory category = statusCalculator.getEffectiveCategory(punishment);
        if (category == ResolvedCategory.BAN) {
            return "banned";
        }
        return category == ResolvedCategory.MUTE ? "muted" : "punished";
    }

    private static String ticketMessage(Ticket ticket, String creatorName) {
        TicketCategory category = ticket.getType();
        StringBuilder message = new StringBuilder(creatorName);
        if (category.isReport()) {
            message.append(": reported ").append(Objects.requireNonNullElse(ticket.getReportedPlayer(), UNKNOWN));
        } else {
            message.append(": created ").append(ticket.getId());
        }
        createdServer(ticket).ifPresent(server -> message.append(" on ").append(server));
        if (category.isReport()) {
            message.append(" (").append(category == TicketCategory.CHAT ? "Chat" : "Gameplay").append(')');
        }
        return message.toString();
    }

    private static Optional<String> createdServer(Ticket ticket) {
        Map<String, Object> data = ticket.getData();
        if (data != null && data.get(CREATED_SERVER) instanceof String server && !server.isBlank()) {
            return Optional.of(server);
        }
        return Optional.empty();
    }

    private static String openingText(@Nullable TicketReply opening) {
        String content = opening != null ? opening.getContent() : null;
        return content == null ? "" : content.replace("**", "").replace("```", "");
    }
}
