package gg.modl.backend.update;

import gg.modl.backend.BackendApplication;
import java.io.File;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

@Component
public class DeploymentDetector {
    public static final String PROPERTY = "modl.deployment";
    public static final String EMBEDDED = "embedded";
    public static final String DOCKER = "docker";

    private final Deployment deployment;

    public DeploymentDetector(@Value("${" + PROPERTY + ":}") @Nullable String configured) {
        this.deployment = detect(configured);
    }

    public Deployment deployment() {
        return deployment;
    }

    public static Deployment detect(@Nullable String configured) {
        String value = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        if (EMBEDDED.equals(value)) {
            return Deployment.EMBEDDED;
        }
        if (DOCKER.equals(value)) {
            return Deployment.DOCKER;
        }
        return runningJar() == null ? Deployment.UNMANAGED : Deployment.JAR;
    }

    @Nullable
    public static File runningJar() {
        File source = new ApplicationHome(BackendApplication.class).getSource();
        return source != null && source.isFile() && source.getName().endsWith(".jar") ? source : null;
    }
}
