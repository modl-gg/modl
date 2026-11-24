package gg.modl.backend.realtime.auth;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.infrastructure.web.SameOriginPolicy;
import gg.modl.backend.instance.PanelLocations;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeOriginValidator {
    static final String MISCONFIGURED_PANEL_URL_NOTICE =
        "Refused a panel live-update connection from {}. The panel URL is {}: open the panel there, or change the Panel URL in "
            + PanelLocations.PANEL_SETTINGS + ".";
    private static final String UNRECOGNIZED_ORIGIN = "an unrecognized origin";

    private final SameOriginPolicy sameOriginPolicy;
    private final OperatorNotice operatorNotice;
    private final AtomicBoolean misconfigurationAnnounced = new AtomicBoolean();

    public boolean isAllowedPanelOrigin(@Nullable String origin, @Nullable HttpOrigin requestOrigin) {
        boolean allowed = requestOrigin != null && sameOriginPolicy.isTrustedOrigin(origin, requestOrigin);
        if (!allowed) {
            reportRejection(HttpOrigin.parse(origin), requestOrigin);
        }
        return allowed;
    }

    private void reportRejection(Optional<HttpOrigin> presented, @Nullable HttpOrigin requestOrigin) {
        String presentedValue = presented.map(HttpOrigin::value).orElse(UNRECOGNIZED_ORIGIN);
        String expected = sameOriginPolicy.publicOrigin().value();
        boolean sameHostDifferentSchemeOrPort = requestOrigin != null
            && presented.filter(origin -> origin.sameHost(requestOrigin)).isPresent();
        if (sameHostDifferentSchemeOrPort && misconfigurationAnnounced.compareAndSet(false, true)) {
            operatorNotice.publish(MISCONFIGURED_PANEL_URL_NOTICE, presentedValue, expected);
            return;
        }
        log.debug("Refused a panel realtime connection from {} (panel URL {})", presentedValue, expected);
    }
}
