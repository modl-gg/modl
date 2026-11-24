package gg.modl.backend.update.install;

import org.jspecify.annotations.Nullable;

public record InstallState(InstallStage stage, @Nullable String targetVersion, @Nullable InstallProblem problem, int downloadPercent) {
    public static final InstallState IDLE = new InstallState(InstallStage.IDLE, null, null, 0);

    public boolean busy() {
        return stage == InstallStage.DOWNLOADING || stage == InstallStage.PREPARING || stage == InstallStage.RESTARTING;
    }
}
