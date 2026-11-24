package gg.modl.backend.player.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import gg.modl.backend.log.service.LogService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import gg.modl.backend.support.TestAddressSettings;
import gg.modl.backend.ticket.service.AppealWorkflowTransitionService;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.webhook.WebhookPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LifecycleHarness {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final UUID PLAYER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final String PLAYER_ID = "player0000000001";
    static final String PLAYER_NAME = "CurrentName";

    final DirectUnitOfWork unitOfWork = new DirectUnitOfWork();
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final PlayerRepository players = mock(PlayerRepository.class);
    final PunishmentRepository punishments = mock(PunishmentRepository.class);
    final PlayerPunishmentLock lock = mock(PlayerPunishmentLock.class);
    final StaffRepository staff = mock(StaffRepository.class);
    final StorageFileRepository storageFiles = mock(StorageFileRepository.class);
    final StoredObjectUrlResolver urls = mock(StoredObjectUrlResolver.class);
    final TicketService tickets = mock(TicketService.class);
    final WebhookPublisher webhooks = mock(WebhookPublisher.class);
    final PunishmentRealtimePublisher realtime = mock(PunishmentRealtimePublisher.class);
    final LogService logs = mock(LogService.class);
    final PermissionService permissions = mock(PermissionService.class);
    final MinecraftIssuerAuthorizer issuerAuthorizer = mock(MinecraftIssuerAuthorizer.class);
    final AppealWorkflowTransitionService appeals = mock(AppealWorkflowTransitionService.class);
    final PunishmentTypesFixture types = new PunishmentTypesFixture();
    final PlayerStatusCalculator statusCalculator = new PlayerStatusCalculator(types.types(), types.thresholds(), clock);
    final IssuerNameResolver issuers = new IssuerNameResolver(staff);
    final EvidenceFileAttachments evidenceFiles = new EvidenceFileAttachments(urls, storageFiles);
    final PunishmentLifecycleService lifecycle;
    final PunishmentMutationService mutations;
    final Player player;

    private final Map<String, PunishmentRecord> stored = new ConcurrentHashMap<>();
    private Punishment inserted;

    LifecycleHarness() {
        lifecycle = new PunishmentLifecycleService(unitOfWork, clock, players, punishments, lock, evidenceFiles, tickets,
            statusCalculator, types.types(), new PunishmentDurationCalculator(types.types(), types.thresholds(), statusCalculator),
            issuers, permissions, mock(RoleAuthorization.class), webhooks, realtime, logs,
            new PunishmentPromotion(unitOfWork, clock, punishments, lock, statusCalculator, realtime), issuerAuthorizer,
            TestAddressSettings.defaults());
        mutations = new PunishmentMutationService(unitOfWork, clock, punishments, tickets, appeals, issuers, lifecycle, realtime,
            statusCalculator);
        player = Player.builder()
            .id(PLAYER_ID)
            .minecraftUuid(PLAYER_UUID)
            .usernames(new ArrayList<>(List.of(new UsernameEntry(PLAYER_NAME, NOW))))
            .build();
        lenient().when(urls.keyOf(any())).thenReturn(Optional.empty());
        lenient().when(players.findByMinecraftUuid(PLAYER_UUID.toString())).thenReturn(Optional.of(player));
        lenient().when(punishments.findById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        lenient().when(punishments.lockById(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));
        lenient().when(punishments.insertIfAbsent(anyString(), any(), any())).thenAnswer(call -> {
            inserted = call.getArgument(1);
            store(inserted);
            return true;
        });
    }

    PunishmentRecord store(Punishment punishment) {
        PunishmentRecord found = new PunishmentRecord(PLAYER_ID, PLAYER_UUID.toString(), PLAYER_NAME, punishment, 4);
        stored.put(punishment.getId(), found);
        return found;
    }

    Punishment inserted() {
        return inserted;
    }
}
