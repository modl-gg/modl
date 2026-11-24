package gg.modl.backend.hostedimport.job;

import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.ImportContext;
import gg.modl.backend.hostedimport.mapping.ImportFit;
import gg.modl.backend.hostedimport.mapping.ImportedIds;
import gg.modl.backend.hostedimport.mapping.ImportedIssuers;
import gg.modl.backend.hostedimport.mapping.PunishmentIds;
import gg.modl.backend.hostedimport.mapping.RoleMapper;
import gg.modl.backend.hostedimport.mapping.SettingsImport;
import gg.modl.backend.hostedimport.mapping.SettingsImport.SettingsImportPlan;
import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.mapping.TicketIds;
import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.repository.ImportTargetRepository;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import gg.modl.backend.player.config.IpHistoryPolicy;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.ticket.config.NotificationExpiry;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportPlanFactory {
    static final List<String> REPLACED_DEFAULT_ROLES = List.of("admin", "moderator", "helper");

    private final ObjectStorage objectStorage;
    private final PermissionService permissionService;
    private final PunishmentTypeService punishmentTypeService;
    private final ImportTargetRepository targets;
    private final IpHistoryPolicy ipHistory;
    private final NotificationExpiry notificationExpiry;
    private final Validator validator;

    public ImportPlan plan(Server server, ArchiveIndex index, IssueLog issues, Instant acceptedAt, @Nullable ImportChoices choices,
                           Map<String, String> ticketAliases, @Nullable String ownerStaffId) {
        ImportedIds ids = new ImportedIds(index.manifest().exportId());
        ImportFit fit = new ImportFit(issues);
        ImportedUrls urls = new ImportedUrls(index.manifest(), index.keyPlan(), objectStorage, issues);
        SettingsImportPlan settings = new SettingsImport(index, validator, urls, fit)
            .plan(punishmentTypeService.getPunishmentTypes(server));
        Instant exportTime = index.manifest().createdAt() != null && index.manifest().createdAt().isBefore(acceptedAt)
            ? index.manifest().createdAt()
            : acceptedAt;
        boolean keepsIpHistory = choices != null && choices.retention().keepsIpHistory();
        ImportContext base = new ImportContext(index, ids, new PunishmentIds(ids, index.punishmentKeepers()),
            new TicketIds(ids, ticketAliases), new ImportedIssuers(Map.of(), Map.of()), settings.types(), urls, issues, fit,
            exportTime, acceptedAt, ipHistory.cutoff(acceptedAt), keepsIpHistory, notificationExpiry.lifetime());
        RoleMapper roles = new RoleMapper(base, permissionService.getBasePermissions(),
            targets.roleNamesLowerExcept(REPLACED_DEFAULT_ROLES));
        StaffPlan staff = StaffPlan.of(index, ids, server.getAdminEmail(), roles);
        Map<String, String> restored = choices == null || ownerStaffId == null
            ? Map.of()
            : staff.restoredStaffIds(choices.staffIds(), choices.mergeArchivedOwner(), ownerStaffId);
        ImportContext context = new ImportContext(index, ids, base.punishmentIds(), base.ticketIds(),
            new ImportedIssuers(restored, staff.archivedUsernames()), settings.types(), urls, issues, fit, exportTime, acceptedAt,
            base.ipCutoff(), keepsIpHistory, base.notificationLifetime());
        return new ImportPlan(context, settings, roles, staff);
    }

    public Map<String, String> liveTicketCollisions(ArchiveIndex index, Map<String, String> knownAliases) {
        TicketIds ticketIds = new TicketIds(new ImportedIds(index.manifest().exportId()), knownAliases);
        Map<String, String> adoptedBySource = new HashMap<>();
        index.ticketIds().forEach(source -> Optional.ofNullable(ticketIds.of(source)).ifPresent(id -> adoptedBySource.put(source, id)));
        Set<String> taken = targets.existingTicketIds(adoptedBySource.values());
        Map<String, String> aliases = new TreeMap<>(knownAliases);
        adoptedBySource.forEach((source, id) -> {
            if (taken.contains(id) && !knownAliases.containsKey(source)) {
                aliases.put(source, ticketIds.collisionAlias(source));
            }
        });
        return aliases;
    }
}
