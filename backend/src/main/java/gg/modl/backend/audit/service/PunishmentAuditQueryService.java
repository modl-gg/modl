package gg.modl.backend.audit.service;

import gg.modl.backend.audit.dto.response.AuditPunishmentResponse;
import gg.modl.backend.audit.dto.response.AuditPunishmentTypeResponse;
import gg.modl.backend.audit.dto.response.PunishmentAuditPage;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentListCursor;
import gg.modl.backend.player.repository.PunishmentListPage;
import gg.modl.backend.player.repository.PunishmentListQuery;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentMapper;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.service.StaffService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentAuditQueryService {
    public static final int DEFAULT_LIST_LIMIT = 200;
    public static final int MAX_LIST_LIMIT = 500;

    private static final String DEFAULT_CATEGORY = "Administrative";
    private static final String UNKNOWN = "Unknown";

    private final PunishmentRepository punishmentRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffService staffService;
    private final Clock clock;

    public PunishmentAuditPage getPunishmentsList(Server server, PunishmentListRequest request, int limit, @Nullable String cursor) {
        Instant now = clock.instant();
        PunishmentListQuery query = request.toQuery(issuerOf(server, request.staff()));
        PunishmentListCursor after = AuditCursors.decode(query, cursor);
        PunishmentListPage page = punishmentRepository.pageListed(query, now, after, limit);
        String next = page.next() == null ? null : AuditCursors.encode(query, page.next());
        return new PunishmentAuditPage(toResponses(server, page.items(), now), next, next != null);
    }

    public List<AuditPunishmentTypeResponse> getPunishmentTypes(Server server) {
        Instant now = clock.instant();
        Map<Integer, Long> activeByType = punishmentRepository.countByType(PunishmentPhase.ACTIVE, now);
        Map<Integer, Long> unstartedByType = punishmentRepository.countByType(PunishmentPhase.UNSTARTED, now);
        Set<Integer> ordinals = new TreeSet<>(activeByType.keySet());
        ordinals.addAll(unstartedByType.keySet());
        punishmentTypeService.getPunishmentTypes(server).stream()
            .map(PunishmentType::getOrdinal)
            .filter(Objects::nonNull)
            .forEach(ordinals::add);
        return ordinals.stream()
            .map(ordinal -> new AuditPunishmentTypeResponse(ordinal, punishmentTypeService.getPunishmentTypeName(server, ordinal),
                activeByType.getOrDefault(ordinal, 0L), unstartedByType.getOrDefault(ordinal, 0L)))
            .toList();
    }

    private @Nullable IssuerMatch issuerOf(Server server, @Nullable String staff) {
        if (staff == null || staff.isBlank()) {
            return null;
        }
        String username = staff.strip();
        return new IssuerMatch(username, staffService.getStaffByUsername(server, username).map(StaffResponse::id).orElse(null));
    }

    private List<AuditPunishmentResponse> toResponses(Server server, List<PunishmentRecord> listed, Instant now) {
        Map<Integer, PunishmentType> types = PunishmentTypeIndex.byOrdinal(punishmentTypeService.getPunishmentTypes(server));
        Map<String, String> staffNames = issuerNameResolver.resolveForPunishments(
            server, listed.stream().map(PunishmentRecord::punishment).toList());
        return listed.stream().map(found -> toResponse(server, found, types, staffNames, now)).toList();
    }

    private AuditPunishmentResponse toResponse(
        Server server, PunishmentRecord found, Map<Integer, PunishmentType> types, Map<String, String> staffNames, Instant now) {
        Punishment punishment = found.punishment();
        int ordinal = punishment.getTypeOrdinal();
        PunishmentType type = types.get(ordinal);
        String category = type != null && type.getCategory() != null ? type.getCategory() : DEFAULT_CATEGORY;
        List<AuditPunishmentResponse.EvidenceItem> evidence = punishment.getEvidence().stream()
            .map(item -> new AuditPunishmentResponse.EvidenceItem(item.text(), item.url(), item.type(), item.fileName()))
            .toList();
        return new AuditPunishmentResponse(
            punishment.getId(),
            found.playerMinecraftUuid(),
            Objects.requireNonNullElse(found.playerUsername(), UNKNOWN),
            punishmentTypeService.getPunishmentTypeName(server, ordinal),
            ordinal,
            category,
            PunishmentMapper.resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), staffNames),
            punishment.data().reason(),
            punishment.data().duration(),
            punishment.getIssued(),
            punishment.getStarted(),
            PunishmentState.displayExpiry(punishment, now),
            PunishmentPhase.of(punishment, now),
            !evidence.isEmpty(),
            evidence.size(),
            evidence,
            punishment.getAttachedTicketIds(),
            punishment.enforcementCategory().displayName());
    }
}
