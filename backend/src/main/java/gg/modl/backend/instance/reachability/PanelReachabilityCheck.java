package gg.modl.backend.instance.reachability;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.PublicUrlChangedEvent;
import gg.modl.backend.instance.ProjectLinks;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.instance.setup.PublicUrlCheck;
import gg.modl.backend.instance.setup.PublicUrlProbe;
import gg.modl.backend.tunnel.TunnelConnectedEvent;
import gg.modl.backend.tunnel.TunnelProperties;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PanelReachabilityCheck {
    static final String WIKI_PAGE = ProjectLinks.PANEL_PORT_AND_TUNNELS;
    static final String UNREACHABLE_NOTICE = "This server could not open the panel at {}. If you cannot open it in your browser"
        + " either, the port is not forwarded to this server or the tunnel is down; see " + WIKI_PAGE
        + ". Some networks cannot reach their own public address, so if the page opens for you, ignore this.";
    static final String LOOPBACK_UNREACHABLE_NOTICE = "This server could not open the panel at {}. Nothing answers at that"
        + " address on this machine; start the panel, or the proxy in front of it, or fix the Panel URL.";
    static final String OTHER_SERVICE_NOTICE = "{} answers, but not with this panel: the address or port points at another"
        + " server or service. Fix the Panel URL or the port forwarding; see " + WIKI_PAGE + ".";

    private final PublicUrlProbe probe;
    private final PublicUrls publicUrls;
    private final OperatorNotice notice;
    private final Clock clock;
    private final Executor executor;
    private final TunnelProperties tunnel;
    private final AtomicReference<PanelReachability> latest = new AtomicReference<>(PanelReachability.NOT_CHECKED);

    @Autowired
    public PanelReachabilityCheck(PublicUrlProbe probe, PublicUrls publicUrls, OperatorNotice notice, Clock clock,
                                  TunnelProperties tunnel) {
        this(probe, publicUrls, notice, clock, task -> Thread.ofVirtual().name("modl-panel-reachability").start(task), tunnel);
    }

    PanelReachabilityCheck(PublicUrlProbe probe, PublicUrls publicUrls, OperatorNotice notice, Clock clock, Executor executor,
                           TunnelProperties tunnel) {
        this.probe = probe;
        this.publicUrls = publicUrls;
        this.notice = notice;
        this.clock = clock;
        this.executor = executor;
        this.tunnel = tunnel;
    }

    public PanelReachability latest() {
        return latest.get();
    }

    @EventListener(InstanceCommittedEvent.class)
    public void checkAfterCommit() {
        if (!tunnel.enabled()) {
            executor.execute(this::check);
        }
    }

    @EventListener(TunnelConnectedEvent.class)
    public void checkAfterTunnelConnected() {
        executor.execute(this::check);
    }

    @EventListener
    public void checkAfterUrlChange(PublicUrlChangedEvent event) {
        executor.execute(this::check);
    }

    void check() {
        Optional<HttpOrigin> origin = publicUrls.knownOrigin();
        if (origin.isEmpty()) {
            latest.set(PanelReachability.unknownUrl(clock.instant()));
            return;
        }
        PanelReachability result;
        try {
            PublicUrlCheck checked = probe.check(origin.get().value());
            result = PanelReachability.of(checked.verdict(), origin.get().value(), clock.instant());
        } catch (RuntimeException e) {
            log.debug("The panel reachability check did not run", e);
            return;
        }
        PanelReachability previous = latest.getAndSet(result);
        if (!result.outcome().equals(previous.outcome()) || !result.checkedUrl().equals(previous.checkedUrl())) {
            announce(result);
        }
    }

    private static boolean isLoopback(String url) {
        return HttpOrigin.parseExact(url).map(HttpOrigin::isLoopback).orElse(false);
    }

    private void announce(PanelReachability result) {
        String url = result.url();
        if (url == null) {
            logUnknownUrl();
            return;
        }
        switch (result.outcome()) {
            case UNREACHABLE -> notice.publish(isLoopback(url) ? LOOPBACK_UNREACHABLE_NOTICE : UNREACHABLE_NOTICE, url);
            case OTHER_SERVICE -> notice.publish(OTHER_SERVICE_NOTICE, url);
            case REACHABLE -> log.info("The panel answers at {}", url);
            case NOT_CHECKED, URL_UNKNOWN -> logUnknownUrl();
        }
    }

    private static void logUnknownUrl() {
        log.debug("The panel address is not known yet");
    }
}
