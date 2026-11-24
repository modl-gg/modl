package gg.modl.backend.update.check;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.PanelLocations;
import gg.modl.backend.update.Deployment;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.SignedRelease;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateNotices {
    private static final String PANEL_INSTRUCTIONS = "Update in " + PanelLocations.UPDATES + ".";

    private final OperatorNotice notice;

    public void releaseAvailable(SignedRelease release, ReleaseVersion current, Deployment deployment) {
        String how = deployment == Deployment.DOCKER ? DockerCommands.howToUpdate(release) : PANEL_INSTRUCTIONS;
        notice.publish("modl {} is available (this server runs {}). {}", release.version(), current, how);
    }
}
