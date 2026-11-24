package gg.modl.backend.player.service;

import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.proto.modl.v1.PanelResource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PunishmentRealtimePublisher {
    private final RealtimeEventPublisher publisher;
    private final SyncProtoFactory syncProtoFactory;
    private final PunishmentTypeService punishmentTypeService;
    private final PlayerStatusCalculator statusCalculator;
    private final IssuerNameResolver issuerNameResolver;
    private final StaffNotices staffNotices;

    public void punishmentIssued(Server server, PunishmentRecord issued) {
        guard(() -> {
            invalidatePanelViews(server, List.of(issued));
            Resolution resolution = resolve(server, List.of(issued));
            publisher.pushPunishments(server,
                toEntries(resolution, List.of(issued)).stream().map(syncProtoFactory::toPendingPunishment).toList(),
                List.of());
            pushIssuedNotice(server, issued, resolution);
        });
    }

    public void punishmentQueued(Server server, PunishmentRecord queued) {
        guard(() -> {
            invalidatePanelViews(server, List.of(queued));
            pushIssuedNotice(server, queued, resolve(server, List.of(queued)));
        });
    }

    public void punishmentsPromoted(Server server, List<PunishmentRecord> promoted) {
        if (promoted.isEmpty()) {
            return;
        }
        guard(() -> {
            invalidatePanelViews(server, promoted);
            publisher.pushPunishments(server,
                toEntries(resolve(server, promoted), promoted).stream().map(syncProtoFactory::toPendingPunishment).toList(),
                List.of());
        });
    }

    public void punishmentModified(Server server, PunishmentRecord modified) {
        guard(() -> {
            invalidatePanelViews(server, List.of(modified));
            pushModified(server, List.of(modified));
        });
    }

    public void punishmentsModified(Server server, List<PunishmentRecord> modified) {
        if (modified.isEmpty()) {
            return;
        }
        guard(() -> {
            invalidateAllPanelViews(server);
            pushModified(server, modified);
        });
    }

    public void punishmentPardoned(Server server, PunishmentRecord pardoned) {
        guard(() -> {
            invalidatePanelViews(server, List.of(pardoned));
            pushPardonNotices(server, pushModified(server, List.of(pardoned)));
        });
    }

    public void punishmentsPardoned(Server server, List<PunishmentRecord> pardoned) {
        if (pardoned.isEmpty()) {
            return;
        }
        guard(() -> {
            invalidateAllPanelViews(server);
            pushPardonNotices(server, pushModified(server, pardoned));
        });
    }

    public void punishmentDetailsChanged(Server server, PunishmentRecord changed) {
        guard(() -> invalidatePanelViews(server, List.of(changed)));
    }

    private List<SyncPunishmentEntry> pushModified(Server server, List<PunishmentRecord> modified) {
        List<SyncPunishmentEntry> entries = toEntries(resolve(server, modified), modified);
        publisher.pushPunishments(server, List.of(), entries.stream().map(syncProtoFactory::toModifiedPunishment).toList());
        return entries;
    }

    private void pushPardonNotices(Server server, List<SyncPunishmentEntry> pardoned) {
        pushStaffNotices(server, pardoned.stream().map(staffNotices::latestPardon).flatMap(Optional::stream).toList());
    }

    private void invalidateAllPanelViews(Server server) {
        publisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_PUNISHMENTS);
        publisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_PLAYERS);
    }

    private void pushIssuedNotice(Server server, PunishmentRecord issued, Resolution resolution) {
        pushStaffNotices(server, List.of(staffNotices.punishmentIssued(issued, resolution.types(), resolution.issuers())));
    }

    private void pushStaffNotices(Server server, List<Map<String, Object>> notices) {
        if (!notices.isEmpty()) {
            publisher.pushStaffNotifications(server, notices.stream().map(syncProtoFactory::toStaffNotification).toList());
        }
    }

    private void invalidatePanelViews(Server server, List<PunishmentRecord> changed) {
        for (PunishmentRecord change : changed) {
            publisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_PUNISHMENTS, change.punishment().getId());
        }
        changed.stream()
            .map(PunishmentRecord::playerMinecraftUuid)
            .distinct()
            .forEach(uuid -> publisher.invalidatePanel(server, PanelResource.PANEL_RESOURCE_PLAYERS, uuid));
    }

    private void guard(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Failed to publish punishment realtime event", e);
        }
    }

    private Resolution resolve(Server server, List<PunishmentRecord> changed) {
        return new Resolution(PunishmentTypeLookup.of(server, punishmentTypeService),
            issuerNameResolver.resolveForPunishments(server, changed.stream().map(PunishmentRecord::punishment).toList()));
    }

    private List<SyncPunishmentEntry> toEntries(Resolution resolution, List<PunishmentRecord> changed) {
        return changed.stream().map(change -> {
            SimplePunishmentView view = PunishmentMapper.toSimplePunishment(
                change.punishment(), resolution.types(), statusCalculator, resolution.issuers());
            return new SyncPunishmentEntry(change.playerMinecraftUuid(), change.playerUsername(), view);
        }).toList();
    }

    private record Resolution(PunishmentTypeLookup types, Map<String, String> issuers) {
    }
}
