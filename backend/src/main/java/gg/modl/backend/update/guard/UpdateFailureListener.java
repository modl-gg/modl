package gg.modl.backend.update.guard;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.util.FailureDescriptions;
import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.JacksonJsonMaps;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdateTransaction;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

public final class UpdateFailureListener implements ApplicationListener<ApplicationFailedEvent> {
    private static final Logger NOTICE = LoggerFactory.getLogger(OperatorNotice.LOGGER_NAME);
    private static final Logger LOG = LoggerFactory.getLogger(UpdateFailureListener.class);

    @Override
    public void onApplicationEvent(ApplicationFailedEvent event) {
        if (event.getApplicationContext() == null) {
            return;
        }
        Environment environment = event.getApplicationContext().getEnvironment();
        UpdateFiles files = new UpdateFiles(new DataDirectoryProperties(environment.getProperty("modl.data-dir")).root(),
            JacksonJsonMaps.INSTANCE);
        try {
            UpdateTransaction pending = files.readState().pending();
            if (pending == null || !pending.to().equals(ReleaseBuild.current().version())) {
                return;
            }
            String reason = FailureDescriptions.describe(event.getException());
            files.writePending(pending.toBuilder().failure(reason).build());
            NOTICE.info("modl {} did not start: {}. {}", pending.to(), reason,
                wayBack(pending, DeploymentDetector.detect(environment.getProperty(DeploymentDetector.PROPERTY))));
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not record the failed update start", e);
        }
    }

    static String wayBack(UpdateTransaction pending, Deployment deployment) {
        boolean supervised = deployment == Deployment.EMBEDDED || System.getenv(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT) != null;
        if (pending.managed() && supervised) {
            return "modl returns to " + pending.from() + " on its own.";
        }
        if (deployment == Deployment.DOCKER) {
            return "Set MODL_IMAGE_TAG=" + pending.from() + " in .env and run docker compose up -d; modl " + pending.from()
                + " restores the database from the pre-update backup by itself.";
        }
        return "Start modl " + pending.from() + " again to restore the database from the pre-update backup, or fix the cause and"
            + " start modl " + pending.to() + " again.";
    }
}
