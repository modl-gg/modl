package gg.modl.backend.tunnel;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.ProjectLinks;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.tunnel.cloudflared.CloudflaredBuild;
import gg.modl.backend.tunnel.cloudflared.CloudflaredInstaller;
import gg.modl.backend.tunnel.cloudflared.CloudflaredLauncher;
import gg.modl.backend.tunnel.cloudflared.CloudflaredRelease;
import gg.modl.backend.tunnel.cloudflared.CloudflaredSupervisor;
import gg.modl.backend.tunnel.cloudflared.HostPlatform;
import gg.modl.backend.tunnel.cloudflared.TunnelDirectory;
import gg.modl.backend.update.check.ReleaseHttpClient;
import gg.modl.backend.update.commit.InstanceCommittedEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CloudflareTunnel implements TunnelStatus, DisposableBean {
    static final String CONNECTED_NOTICE = "Cloudflare Tunnel connected: {}";
    static final String NOT_CONNECTED_NOTICE = "Cloudflare Tunnel is not connected after 1 minute. Check the tunnel token"
        + " (MODL_TUNNEL_TOKEN or connection.yml embedded.tunnel.token) and that this host allows outbound connections to"
        + " Cloudflare on port 7844; modl keeps retrying.";
    static final String UNAVAILABLE_NOTICE = "Cloudflare Tunnel cannot start: {}. modl keeps retrying.";
    static final String UNSUPPORTED_NOTICE = "Cloudflare Tunnel cannot start: modl has no cloudflared build for {}. Run"
        + " cloudflared yourself instead, see " + ProjectLinks.PANEL_PORT_AND_TUNNELS + ".";
    static final Duration DOWNLOAD_BUDGET = Duration.ofMinutes(15);

    private static final Logger CLOUDFLARED_OUTPUT = LoggerFactory.getLogger("modl.tunnel.cloudflared");

    private final TunnelProperties properties;
    private final DataDirectoryProperties dataDirectory;
    private final ReleaseHttpClient http;
    private final PublicUrls publicUrls;
    private final OperatorNotice notice;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final HostPlatform platform;
    private volatile @Nullable CloudflaredSupervisor supervisor;
    private volatile @Nullable TunnelState unsupported;
    private boolean closed;

    public CloudflareTunnel(TunnelProperties properties, DataDirectoryProperties dataDirectory, ReleaseHttpClient http,
                            PublicUrls publicUrls, OperatorNotice notice, ApplicationEventPublisher events, Clock clock) {
        this.properties = properties;
        this.dataDirectory = dataDirectory;
        this.http = http;
        this.publicUrls = publicUrls;
        this.notice = notice;
        this.events = events;
        this.clock = clock;
        this.platform = HostPlatform.current();
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public TunnelState state() {
        if (!properties.enabled()) {
            return TunnelState.OFF;
        }
        TunnelState refused = unsupported;
        if (refused != null) {
            return refused;
        }
        CloudflaredSupervisor running = supervisor;
        return running == null ? TunnelState.CONNECTING : running.state();
    }

    @Override
    public String address() {
        return publicUrls.panel();
    }

    @EventListener(InstanceCommittedEvent.class)
    public synchronized void start() {
        String token = properties.token();
        if (closed || !properties.enabled() || token == null || supervisor != null || unsupported != null) {
            return;
        }
        Optional<CloudflaredBuild> build = CloudflaredRelease.pinned().forPlatform(platform);
        if (build.isEmpty()) {
            unsupported = TunnelState.unavailable("no cloudflared build for " + platform.describe());
            notice.publish(UNSUPPORTED_NOTICE, platform.describe());
            return;
        }
        TunnelDirectory directory = TunnelDirectory.in(dataDirectory.root());
        CloudflaredSupervisor started = new CloudflaredSupervisor(
            new CloudflaredInstaller(directory, build.get(),
                (source, target, size, progress) -> http.download(source, target, size, DOWNLOAD_BUDGET, progress)),
            new CloudflaredLauncher(directory, token, platform), new Notices(), CloudflaredSupervisor.Timing.standard(), clock,
            token, CLOUDFLARED_OUTPUT::info);
        supervisor = started;
        log.info("Starting Cloudflare Tunnel for {} with cloudflared {}", address(), build.get().version());
        started.start();
    }

    @Override
    public void destroy() {
        CloudflaredSupervisor running;
        synchronized (this) {
            closed = true;
            running = supervisor;
        }
        if (running != null) {
            running.stop();
        }
    }

    private final class Notices implements TunnelEvents {
        @Override
        public void connected() {
            notice.publish(CONNECTED_NOTICE, address());
            events.publishEvent(new TunnelConnectedEvent());
        }

        @Override
        public void notConnected() {
            notice.publish(NOT_CONNECTED_NOTICE);
        }

        @Override
        public void unavailable(String reason) {
            notice.publish(UNAVAILABLE_NOTICE, reason);
        }
    }
}
