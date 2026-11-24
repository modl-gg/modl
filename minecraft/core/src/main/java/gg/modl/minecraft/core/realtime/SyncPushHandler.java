package gg.modl.minecraft.core.realtime;

import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.service.sync.SyncService;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.RecentIds;
import gg.modl.proto.modl.v1.ActiveStaffPushEvent;
import gg.modl.proto.modl.v1.PlayerNotificationPushEvent;
import gg.modl.proto.modl.v1.PunishmentPushEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.RealtimeEnvelope.PayloadCase;
import gg.modl.proto.modl.v1.Staff2faPushEvent;
import gg.modl.proto.modl.v1.StaffNotificationPushEvent;
import gg.modl.proto.modl.v1.StatWipePushEvent;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public final class SyncPushHandler implements RealtimeInbound, ConnectionListener {
    private static final int RECENT_EVENT_ID_CAPACITY = 256;

    private final SyncService syncService;
    private final PluginLogger logger;
    private final boolean debugMode;
    private final RecentIds recentEventIds = new RecentIds(RECENT_EVENT_ID_CAPACITY);
    private final Map<PayloadCase, Consumer<RealtimeEnvelope>> appliers;

    public SyncPushHandler(SyncService syncService, PluginLogger logger, boolean debugMode) {
        this.syncService = syncService;
        this.logger = logger;
        this.debugMode = debugMode;
        this.appliers = createAppliers();
    }

    public void attachTo(RealtimeConnection connection) {
        for (PayloadCase payloadCase : appliers.keySet()) {
            connection.register(payloadCase, this);
        }
        connection.addListener(this);
    }

    @Override
    public void receive(RealtimeEnvelope envelope) {
        Runnable action = domainAction(envelope);
        if (action != null) applyDomainEvent(envelope, action);
    }

    @Override
    public void onConnected(boolean first) {
        syncService.setRealtimeConnected(true);
        syncService.runBaselineFetch(first ? "realtime connect" : "realtime reconnect");
    }

    @Override
    public void onDisconnected() {
        syncService.setRealtimeConnected(false);
    }

    @Override
    public void onResyncAdvised() {
        syncService.runBaselineFetch("realtime resync advice");
    }

    @Override
    public void onReplaced() {
    }

    @Override
    public void onTerminated(TerminationReason reason) {
    }

    @Nullable Runnable domainAction(RealtimeEnvelope envelope) {
        Consumer<RealtimeEnvelope> applier = appliers.get(envelope.getPayloadCase());
        return applier == null ? null : () -> applier.accept(envelope);
    }

    private Map<PayloadCase, Consumer<RealtimeEnvelope>> createAppliers() {
        Map<PayloadCase, Consumer<RealtimeEnvelope>> byCase = new EnumMap<>(PayloadCase.class);
        byCase.put(PayloadCase.PERMISSION_INVALIDATED, envelope -> syncService.refreshStaffPermissionsNow());
        byCase.put(PayloadCase.SYNC_REQUESTED, envelope -> syncService.runBaselineFetch("backend requested a sync"));
        byCase.put(PayloadCase.PUNISHMENT_TYPE_INVALIDATED, envelope -> syncService.refreshPunishmentTypesNow());
        byCase.put(PayloadCase.PUNISHMENT_PUSH, envelope -> applyPunishmentPush(envelope.getPunishmentPush()));
        byCase.put(PayloadCase.PLAYER_NOTIFICATION_PUSH,
            envelope -> applyPlayerNotificationPush(envelope.getPlayerNotificationPush()));
        byCase.put(PayloadCase.STAFF_NOTIFICATION_PUSH,
            envelope -> applyStaffNotificationPush(envelope.getStaffNotificationPush()));
        byCase.put(PayloadCase.STAT_WIPE_PUSH, envelope -> applyStatWipePush(envelope.getStatWipePush()));
        byCase.put(PayloadCase.STAFF_2FA_PUSH, envelope -> applyStaff2faPush(envelope.getStaff2FaPush()));
        byCase.put(PayloadCase.ACTIVE_STAFF_PUSH, envelope -> applyActiveStaffPush(envelope.getActiveStaffPush()));
        return Collections.unmodifiableMap(byCase);
    }

    private void applyDomainEvent(RealtimeEnvelope envelope, Runnable apply) {
        String eventId = envelope.getEventId();
        boolean accepted = syncService.submitRealtimeApply(() -> {
            if (recentEventIds.contains(eventId)) {
                if (debugMode) logger.info("[Realtime] Suppressed duplicate event " + eventId);
                return;
            }
            try {
                apply.run();
                recentEventIds.markIfNew(eventId);
            } catch (Exception e) {
                logger.warning("[Realtime] Failed to apply " + envelope.getPayloadCase()
                    + " (reconciled on next baseline sync): " + e.getMessage());
            }
        });
        if (!accepted && debugMode) {
            logger.info("[Realtime] Dropped " + envelope.getPayloadCase() + "; sync service unavailable");
        }
    }

    private void applyPunishmentPush(PunishmentPushEvent event) {
        SyncResponse.SyncData data = RealtimeEventMappers.fromPunishmentPush(event);
        for (SyncResponse.ModifiedPunishment modified : data.getRecentlyModifiedPunishments()) {
            syncService.applyModifiedPunishment(modified);
        }
        for (SyncResponse.PendingPunishment pending : data.getPendingPunishments()) {
            syncService.applyPendingPunishment(pending);
        }
    }

    private void applyPlayerNotificationPush(PlayerNotificationPushEvent event) {
        SyncResponse.SyncData data = RealtimeEventMappers.fromPlayerNotificationPush(event);
        for (SyncResponse.PlayerNotification notification : data.getPlayerNotifications()) {
            String target = notification.getTargetPlayerUuid();
            if (target == null || target.isEmpty()) {
                if (debugMode) logger.info("[Realtime] Dropping player notification " + notification.getId() + " with empty target");
                continue;
            }
            syncService.applyPlayerNotification(notification);
        }
    }

    private void applyStaffNotificationPush(StaffNotificationPushEvent event) {
        SyncResponse.SyncData data = RealtimeEventMappers.fromStaffNotificationPush(event);
        for (SyncResponse.StaffNotification notification : data.getStaffNotifications()) {
            syncService.applyStaffNotification(notification);
        }
    }

    private void applyStatWipePush(StatWipePushEvent event) {
        SyncResponse.SyncData data = RealtimeEventMappers.fromStatWipePush(event);
        for (SyncResponse.PendingStatWipe statWipe : data.getPendingStatWipes()) {
            syncService.applyStatWipe(statWipe);
        }
    }

    private void applyStaff2faPush(Staff2faPushEvent event) {
        SyncResponse.SyncData data = RealtimeEventMappers.fromStaff2faPush(event);
        for (SyncResponse.Staff2faVerification verification : data.getStaff2faVerifications()) {
            syncService.applyStaff2faVerification(verification);
        }
    }

    private void applyActiveStaffPush(ActiveStaffPushEvent event) {
        SyncResponse.SyncData data = RealtimeEventMappers.fromActiveStaffPush(event);
        for (SyncResponse.ActiveStaffMember staffMember : data.getActiveStaffMembers()) {
            syncService.applyActiveStaffMember(staffMember);
        }
    }
}
