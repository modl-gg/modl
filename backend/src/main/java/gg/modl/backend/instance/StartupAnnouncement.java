package gg.modl.backend.instance;

import gg.modl.backend.Constants;
import gg.modl.backend.update.ReleaseBuild;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class StartupAnnouncement {
    private final PublicUrls publicUrls;

    @EventListener(WebServerInitializedEvent.class)
    public void announce() {
        log.info("{} {} listening on {}", Constants.BRAND_NAME, ReleaseBuild.current().versionText(), publicUrls.origin());
    }
}
