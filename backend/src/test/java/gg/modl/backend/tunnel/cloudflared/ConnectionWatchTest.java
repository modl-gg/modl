package gg.modl.backend.tunnel.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.tunnel.TunnelPhase;
import gg.modl.backend.tunnel.cloudflared.ConnectionWatch.Announcement;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConnectionWatchTest {
    private static final Instant START = Instant.parse("2026-11-24T12:00:00Z");

    private final ConnectionWatch watch = new ConnectionWatch(Duration.ofMinutes(1));

    @Test
    void theMinuteStartsWhenCloudflaredFirstRunsNotWhileItIsDownloaded() {
        assertThat(watch.check(START.plusSeconds(600))).isEqualTo(Announcement.NONE);

        watch.launched(START.plusSeconds(600));

        assertThat(watch.check(START.plusSeconds(659))).isEqualTo(Announcement.NONE);
        assertThat(watch.check(START.plusSeconds(660))).isEqualTo(Announcement.NOT_CONNECTED);
        assertThat(watch.check(START.plusSeconds(900))).isEqualTo(Announcement.NONE);
        assertThat(watch.state().phase()).isEqualTo(TunnelPhase.NOT_CONNECTED);
    }

    @Test
    void aCrashLoopKeepsTheFirstLaunchAsTheStartOfTheMinute() {
        watch.launched(START);
        watch.exited(START.plusSeconds(20));
        watch.launched(START.plusSeconds(40));

        assertThat(watch.check(START.plusSeconds(60))).isEqualTo(Announcement.NOT_CONNECTED);
    }

    @Test
    void theFirstConnectionIsAnnouncedAndABriefDropIsNot() {
        watch.launched(START);
        assertThat(watch.connected()).isEqualTo(Announcement.CONNECTED);
        assertThat(watch.connected()).isEqualTo(Announcement.NONE);

        watch.disconnected(START.plusSeconds(100));
        assertThat(watch.state().phase()).isEqualTo(TunnelPhase.CONNECTING);
        assertThat(watch.check(START.plusSeconds(130))).isEqualTo(Announcement.NONE);

        assertThat(watch.connected()).isEqualTo(Announcement.NONE);
    }

    @Test
    void aReconnectAfterTheFailureNoticeIsAnnouncedAgain() {
        watch.launched(START);
        watch.connected();
        watch.disconnected(START.plusSeconds(10));

        assertThat(watch.check(START.plusSeconds(70))).isEqualTo(Announcement.NOT_CONNECTED);
        assertThat(watch.connected()).isEqualTo(Announcement.CONNECTED);
    }

    @Test
    void anInstallProblemIsAnnouncedOncePerEpisodeWhateverItsText() {
        assertThat(watch.unavailable("connect timed out")).isEqualTo(Announcement.UNAVAILABLE);
        assertThat(watch.unavailable("Read timed out")).isEqualTo(Announcement.NONE);
        assertThat(watch.state().problem()).isEqualTo("Read timed out");

        watch.launched(START);
        assertThat(watch.state().phase()).isEqualTo(TunnelPhase.CONNECTING);
        assertThat(watch.unavailable("HTTP 503")).isEqualTo(Announcement.NONE);
    }

    @Test
    void afterAnInstallProblemCloudflaredStillGetsItsOwnMinute() {
        watch.unavailable("no access to github.com");
        watch.launched(START);

        assertThat(watch.check(START.plusSeconds(60))).isEqualTo(Announcement.NOT_CONNECTED);
        assertThat(watch.connected()).isEqualTo(Announcement.CONNECTED);
    }
}
