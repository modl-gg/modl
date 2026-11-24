package gg.modl.backend.server.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.repository.InstanceRepository;
import gg.modl.backend.realtime.dispatch.RealtimeEventDispatcher;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.PermissionInvalidatedEvent;
import gg.modl.proto.modl.v1.PunishmentTypeInvalidatedEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerTimestampService {
    private static final String STAFF_PERMISSIONS_TIMESTAMP_FIELD = "staffPermissionsUpdatedAt";
    private static final String PUNISHMENT_TYPES_TIMESTAMP_FIELD = "punishmentTypesUpdatedAt";

    private final InstanceRepository instanceRepository;
    private final UnitOfWork unitOfWork;
    private final RealtimeEventDispatcher realtimeEventDispatcher;
    private final InstanceContext instanceContext;
    private final Clock clock;
    private long lastTimestampMillis;

    public void updateStaffPermissionsTimestamp(Server server) {
        unitOfWork.write(() -> {
            Instant timestamp = nextTimestamp();
            instanceRepository.updateStaffPermissionsTimestamp(timestamp);
            unitOfWork.afterCommit(() -> refreshAndPublish(server, timestamp, STAFF_PERMISSIONS_TIMESTAMP_FIELD,
                Topic.TOPIC_MINECRAFT_PERMISSIONS,
                envelope -> envelope.setPermissionInvalidated(PermissionInvalidatedEvent.newBuilder()),
                "Failed to dispatch realtime staff permissions invalidation"));
        });
    }

    public void updatePunishmentTypesTimestamp(Server server) {
        unitOfWork.write(() -> {
            Instant timestamp = nextTimestamp();
            instanceRepository.updatePunishmentTypesTimestamp(timestamp);
            unitOfWork.afterCommit(() -> refreshAndPublish(server, timestamp, PUNISHMENT_TYPES_TIMESTAMP_FIELD,
                Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES,
                envelope -> envelope.setPunishmentTypeInvalidated(PunishmentTypeInvalidatedEvent.newBuilder()),
                "Failed to dispatch realtime punishment type invalidation"));
        });
    }

    private void refreshAndPublish(Server server, Instant timestamp, String timestampField, Topic topic,
                                   Consumer<RealtimeEnvelope.Builder> payloadSetter, String failureMessage) {
        instanceContext.refresh();
        publishInvalidation(server, timestamp, timestampField, topic, payloadSetter, failureMessage);
    }

    private void publishInvalidation(Server server, Instant timestamp, String timestampField, Topic topic,
                                     Consumer<RealtimeEnvelope.Builder> payloadSetter, String failureMessage) {
        try {
            RealtimeEnvelope.Builder envelope = RealtimeEnvelope.newBuilder()
                .setEventId(eventId(server, timestampField, timestamp));
            payloadSetter.accept(envelope);
            realtimeEventDispatcher.publish(new RealtimeOutboundEvent(server.getId(), topic, envelope.build()));
        } catch (Exception e) {
            log.warn(failureMessage, e);
        }
    }

    private synchronized Instant nextTimestamp() {
        long timestampMillis = clock.millis();
        if (timestampMillis <= lastTimestampMillis) {
            timestampMillis = lastTimestampMillis + 1;
        }
        lastTimestampMillis = timestampMillis;
        return Instant.ofEpochMilli(timestampMillis);
    }

    private String eventId(Server server, String timestampField, Instant timestamp) {
        return server.getId() + "::" + timestampField + "::" + timestamp;
    }
}
