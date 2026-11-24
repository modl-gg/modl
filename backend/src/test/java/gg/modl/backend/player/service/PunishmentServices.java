package gg.modl.backend.player.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.log.service.LogService;
import gg.modl.backend.player.PunishmentStore;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import gg.modl.backend.support.TestAddressSettings;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.service.AppealWorkflowTransitionService;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.webhook.WebhookPublisher;
import java.util.Optional;

public record PunishmentServices(
    PunishmentStore store,
    PlayerStatusCalculator statusCalculator,
    IssuerNameResolver issuerNameResolver,
    PunishmentRealtimePublisher realtime,
    PunishmentLifecycleService lifecycle,
    PunishmentMutationService mutations
) {
    public static PunishmentServices on(SqlTestDatabase database, PunishmentStore store, PunishmentTypeService types,
                                        OffenderThresholdSettingsService thresholds) {
        PlayerStatusCalculator statusCalculator = new PlayerStatusCalculator(types, thresholds, database.clock());
        IssuerNameResolver issuers = new IssuerNameResolver(mock(StaffRepository.class));
        StoredObjectUrlResolver urls = mock(StoredObjectUrlResolver.class);
        when(urls.keyOf(any())).thenReturn(Optional.empty());
        PunishmentRealtimePublisher realtime = mock(PunishmentRealtimePublisher.class);
        TicketService tickets = mock(TicketService.class);
        PunishmentLifecycleService lifecycle = new PunishmentLifecycleService(
            database.unitOfWork(),
            database.clock(),
            store.players(),
            store.punishments(),
            store.lock(),
            new EvidenceFileAttachments(urls, mock(StorageFileRepository.class)),
            tickets,
            statusCalculator,
            types,
            new PunishmentDurationCalculator(types, thresholds, statusCalculator),
            issuers,
            mock(PermissionService.class),
            mock(RoleAuthorization.class),
            mock(WebhookPublisher.class),
            realtime,
            mock(LogService.class),
            new PunishmentPromotion(database.unitOfWork(), database.clock(), store.punishments(), store.lock(), statusCalculator,
                realtime),
            mock(MinecraftIssuerAuthorizer.class),
            TestAddressSettings.defaults());
        PunishmentMutationService mutations = new PunishmentMutationService(
            database.unitOfWork(),
            database.clock(),
            store.punishments(),
            tickets,
            mock(AppealWorkflowTransitionService.class),
            issuers,
            lifecycle,
            realtime,
            statusCalculator);
        return new PunishmentServices(store, statusCalculator, issuers, realtime, lifecycle, mutations);
    }
}
