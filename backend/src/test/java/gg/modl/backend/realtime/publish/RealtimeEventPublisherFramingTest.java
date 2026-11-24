package gg.modl.backend.realtime.publish;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.realtime.config.RealtimeProperties;
import gg.modl.backend.realtime.dispatch.RealtimeDispatchResult;
import gg.modl.backend.realtime.dispatch.RealtimeOutboundEvent;
import gg.modl.backend.realtime.transport.RealtimeCodec;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.SimplePunishment;
import gg.modl.proto.modl.v1.SyncModifiedPunishment;
import gg.modl.proto.modl.v1.SyncPendingPunishment;
import gg.modl.proto.modl.v1.SyncPunishmentWithModifications;
import gg.modl.proto.modl.v1.SyncRequestReason;
import gg.modl.proto.modl.v1.Topic;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RealtimeEventPublisherFramingTest {
    private static final int MAX_FRAME_BYTES = 4096;
    private static final int ITEMS = 60;

    private final List<RealtimeOutboundEvent> published = new ArrayList<>();
    private final RealtimeProperties properties = properties();
    private final RealtimeEventPublisher publisher = new RealtimeEventPublisher(event -> {
        published.add(event);
        return new RealtimeDispatchResult(0, 0, 0);
    }, properties);

    @Test
    void manyLargeModifiedPunishmentsAreSplitIntoFramesUnderTheFrameLimit() {
        List<SyncModifiedPunishment> modified = IntStream.range(0, ITEMS)
            .mapToObj(index -> modified("P" + index, 300))
            .toList();

        publisher.pushPunishments(server(), List.of(), modified);

        assertThat(published).hasSizeGreaterThan(1);
        RealtimeCodec codec = new RealtimeCodec(properties);
        int delivered = 0;
        for (RealtimeOutboundEvent event : published) {
            assertThat(event.topic()).isEqualTo(Topic.TOPIC_MINECRAFT_PUNISHMENTS);
            assertThat(codec.outboundPayload(event.envelope()).length).isLessThanOrEqualTo(MAX_FRAME_BYTES);
            delivered += event.envelope().getPunishmentPush().getModifiedCount();
        }
        assertThat(delivered).isEqualTo(ITEMS);
    }

    @Test
    void modifiedPunishmentsAreDeliveredBeforePendingOnes() {
        publisher.pushPunishments(server(), List.of(pending("NEW1")), List.of(modified("OLD1", 10)));

        assertThat(published).hasSize(2);
        assertThat(published.get(0).envelope().getPunishmentPush().getModifiedCount()).isEqualTo(1);
        assertThat(published.get(1).envelope().getPunishmentPush().getPendingCount()).isEqualTo(1);
    }

    @Test
    void aPushThatCannotFitInOneFrameAsksTheServersToResyncInstead() {
        publisher.pushPunishments(server(), List.of(), List.of(modified("HUGE", MAX_FRAME_BYTES * 2)));

        assertThat(published).hasSize(1);
        RealtimeEnvelope envelope = published.get(0).envelope();
        assertThat(envelope.getPayloadCase()).isEqualTo(RealtimeEnvelope.PayloadCase.SYNC_REQUESTED);
        assertThat(envelope.getSyncRequested().getReason()).isEqualTo(SyncRequestReason.SYNC_REQUEST_REASON_PUSH_TOO_LARGE);
        assertThat(published.get(0).topic()).isEqualTo(Topic.TOPIC_MINECRAFT_PUNISHMENTS);
    }

    private static SyncModifiedPunishment modified(String id, int paddingBytes) {
        return SyncModifiedPunishment.newBuilder()
            .setMinecraftUuid("00000000-0000-0000-0000-000000000001")
            .setUsername("x".repeat(paddingBytes))
            .setPunishment(SyncPunishmentWithModifications.newBuilder().setId(id))
            .build();
    }

    private static SyncPendingPunishment pending(String id) {
        return SyncPendingPunishment.newBuilder()
            .setMinecraftUuid("00000000-0000-0000-0000-000000000002")
            .setUsername("Target")
            .setPunishment(SimplePunishment.newBuilder().setId(id))
            .build();
    }

    private static RealtimeProperties properties() {
        RealtimeProperties properties = new RealtimeProperties();
        properties.setMaxFrameBytes(MAX_FRAME_BYTES);
        return properties;
    }

    private static Server server() {
        Server server = TestServers.server("server", "admin@example.com");
        server.setId("instance");
        return server;
    }
}
