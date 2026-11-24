package gg.modl.backend.instance.reachability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.instance.setup.PublicUrlCheck;
import gg.modl.backend.instance.setup.PublicUrlProbe;
import gg.modl.backend.instance.setup.PublicUrlVerdict;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanelReachabilityCheckTest {
    private static final String PANEL = "http://203.0.113.7:8080";

    private PublicUrlProbe probe;
    private PublicUrls urls;
    private OperatorNotice notice;
    private PanelReachabilityCheck check;

    @BeforeEach
    void setUp() {
        probe = mock(PublicUrlProbe.class);
        urls = mock(PublicUrls.class);
        notice = mock(OperatorNotice.class);
        check = new PanelReachabilityCheck(probe, urls, notice, Clock.systemUTC(), Runnable::run);
        when(urls.knownOrigin()).thenReturn(HttpOrigin.parseExact(PANEL));
    }

    @Test
    void anUnreachablePanelUrlIsAnnouncedOnceWithTheTunnelGuide() {
        when(probe.check(PANEL)).thenReturn(new PublicUrlCheck(PublicUrlVerdict.UNREACHABLE, PANEL));

        check.checkAfterCommit();
        check.checkAfterCommit();

        verify(notice, times(1)).publish(eq(PanelReachabilityCheck.UNREACHABLE_NOTICE), eq(PANEL));
        assertThat(PanelReachabilityCheck.UNREACHABLE_NOTICE).contains("wiki/Panel-Port-and-Tunnels");
        assertThat(check.latest().outcome()).isEqualTo(PanelReachability.Outcome.UNREACHABLE);
    }

    @Test
    void anUnreachableLoopbackPanelUrlNamesThisMachineInsteadOfPortForwarding() {
        String local = "http://localhost:3000";
        when(urls.knownOrigin()).thenReturn(HttpOrigin.parseExact(local));
        when(probe.check(local)).thenReturn(new PublicUrlCheck(PublicUrlVerdict.UNREACHABLE, local));

        check.checkAfterCommit();

        verify(notice).publish(eq(PanelReachabilityCheck.LOOPBACK_UNREACHABLE_NOTICE), eq(local));
        verify(notice, never()).publish(eq(PanelReachabilityCheck.UNREACHABLE_NOTICE), any(Object[].class));
    }

    @Test
    void anotherServiceOnThePanelUrlIsAProblem() {
        when(probe.check(PANEL)).thenReturn(new PublicUrlCheck(PublicUrlVerdict.OTHER_SERVICE, PANEL));

        check.checkAfterCommit();

        verify(notice).publish(eq(PanelReachabilityCheck.OTHER_SERVICE_NOTICE), eq(PANEL));
    }

    @Test
    void aReachablePanelStaysQuietInTheConsole() {
        when(probe.check(PANEL)).thenReturn(new PublicUrlCheck(PublicUrlVerdict.REACHABLE, PANEL));

        check.checkAfterCommit();

        verify(notice, never()).publish(any(), any(Object[].class));
        assertThat(check.latest().outcome()).isEqualTo(PanelReachability.Outcome.REACHABLE);
    }

    @Test
    void withoutAPanelUrlNothingIsProbed() {
        when(urls.knownOrigin()).thenReturn(Optional.empty());

        check.checkAfterCommit();

        verify(probe, never()).check(any());
        assertThat(check.latest().outcome()).isEqualTo(PanelReachability.Outcome.URL_UNKNOWN);
    }
}
