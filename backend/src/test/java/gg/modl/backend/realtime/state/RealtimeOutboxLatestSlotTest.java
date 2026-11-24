package gg.modl.backend.realtime.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;

class RealtimeOutboxLatestSlotTest {
    @Test
    void anUnsentSnapshotIsReplacedInPlaceByTheNewerOne() {
        RealtimeOutbox outbox = new RealtimeOutbox(10, 65_536);

        assertThat(outbox.offer(message("first"))).isEqualTo(RealtimeOutbox.Admission.STARTS_DRAIN);
        assertThat(outbox.offerLatest("moderation:a", message("old"))).isEqualTo(RealtimeOutbox.Admission.QUEUED);
        assertThat(outbox.offer(message("after"))).isEqualTo(RealtimeOutbox.Admission.QUEUED);
        assertThat(outbox.offerLatest("moderation:a", message("new"))).isEqualTo(RealtimeOutbox.Admission.QUEUED);
        assertThat(outbox.offerLatest("moderation:b", message("other"))).isEqualTo(RealtimeOutbox.Admission.QUEUED);

        assertThat(drained(outbox)).isEqualTo("first,new,after,other,");
    }

    @Test
    void aSentSnapshotIsNotReplacedAndCountsOnlyOnceAgainstTheLimit() {
        RealtimeOutbox outbox = new RealtimeOutbox(2, 65_536);
        outbox.offerLatest("moderation:a", message("one"));
        for (int index = 0; index < 50; index++) {
            assertThat(outbox.offerLatest("moderation:a", message("again" + index)).isAccepted()).isTrue();
        }
        assertThat(outbox.queuedMessages()).isEqualTo(1);

        assertThat(drained(outbox)).isEqualTo("again49,");
        assertThat(outbox.offerLatest("moderation:a", message("later"))).isEqualTo(RealtimeOutbox.Admission.STARTS_DRAIN);
    }

    private static BinaryMessage message(String text) {
        return new BinaryMessage(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String drained(RealtimeOutbox outbox) {
        StringBuilder sent = new StringBuilder();
        for (RealtimeOutbox.Outbound next = outbox.next(); next != null; next = outbox.next()) {
            RealtimeOutbox.Send queued = assertInstanceOf(RealtimeOutbox.Send.class, next);
            sent.append(StandardCharsets.UTF_8.decode(queued.message().getPayload().duplicate())).append(',');
        }
        return sent.toString();
    }
}
