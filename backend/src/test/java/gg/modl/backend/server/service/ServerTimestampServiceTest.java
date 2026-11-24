package gg.modl.backend.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.Topic;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ServerTimestampServiceTest {
    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private ServerTimestampService service;
    private Server server;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        service = kit.timestamps();
        server = kit.server();
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void updateStaffPermissionsTimestampPublishesPermissionInvalidation() {
        service.updateStaffPermissionsTimestamp(server);

        Instant timestamp = kit.server().getStaffPermissionsUpdatedAt();
        RealtimeOutboundEvent event = published(1).getFirst();
        assertThat(event.serverId()).isEqualTo("instance");
        assertThat(event.topic()).isEqualTo(Topic.TOPIC_MINECRAFT_PERMISSIONS);
        assertThat(event.envelope().getEventId()).isEqualTo("instance::staffPermissionsUpdatedAt::" + timestamp);
        assertThat(event.envelope().getPayloadCase()).isEqualTo(RealtimeEnvelope.PayloadCase.PERMISSION_INVALIDATED);
    }

    @Test
    void updatePunishmentTypesTimestampPublishesPunishmentTypeInvalidation() {
        service.updatePunishmentTypesTimestamp(server);

        Instant timestamp = kit.server().getPunishmentTypesUpdatedAt();
        RealtimeOutboundEvent event = published(1).getFirst();
        assertThat(event.topic()).isEqualTo(Topic.TOPIC_MINECRAFT_PUNISHMENT_TYPES);
        assertThat(event.envelope().getEventId()).isEqualTo("instance::punishmentTypesUpdatedAt::" + timestamp);
        assertThat(event.envelope().getPayloadCase()).isEqualTo(RealtimeEnvelope.PayloadCase.PUNISHMENT_TYPE_INVALIDATED);
    }

    @Test
    void updateStaffPermissionsTimestampPublishesDistinctEventIdsForSeparateTimestamps() {
        service.updateStaffPermissionsTimestamp(server);
        Instant first = kit.server().getStaffPermissionsUpdatedAt();
        service.updateStaffPermissionsTimestamp(server);

        assertThat(kit.server().getStaffPermissionsUpdatedAt()).isAfter(first);
        assertDistinctEventIds();
    }

    @Test
    void updatePunishmentTypesTimestampPublishesDistinctEventIdsForSeparateTimestamps() {
        service.updatePunishmentTypesTimestamp(server);
        Instant first = kit.server().getPunishmentTypesUpdatedAt();
        service.updatePunishmentTypesTimestamp(server);

        assertThat(kit.server().getPunishmentTypesUpdatedAt()).isAfter(first);
        assertDistinctEventIds();
    }

    @Test
    void updateStaffPermissionsTimestampDoesNotPropagateDispatchFailure() {
        doThrow(new RuntimeException("dispatch failed")).when(kit.dispatcher()).publish(any(RealtimeOutboundEvent.class));

        assertThatCode(() -> service.updateStaffPermissionsTimestamp(server)).doesNotThrowAnyException();

        assertThat(kit.server().getStaffPermissionsUpdatedAt()).isNotNull();
    }

    @Test
    void nothingIsPublishedWhenTheOuterUnitRollsBack() {
        assertThatThrownBy(() -> kit.unitOfWork().write(() -> {
            service.updatePunishmentTypesTimestamp(server);
            throw new IllegalStateException("the outer unit fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(kit.instanceRepository().find().orElseThrow().getPunishmentTypesUpdatedAt()).isNull();
        verify(kit.dispatcher(), never()).publish(any(RealtimeOutboundEvent.class));
    }

    private void assertDistinctEventIds() {
        assertThat(published(2)).extracting(event -> event.envelope().getEventId()).doesNotHaveDuplicates();
    }

    private List<RealtimeOutboundEvent> published(int count) {
        ArgumentCaptor<RealtimeOutboundEvent> events = ArgumentCaptor.forClass(RealtimeOutboundEvent.class);
        verify(kit.dispatcher(), times(count)).publish(events.capture());
        return events.getAllValues();
    }
}
