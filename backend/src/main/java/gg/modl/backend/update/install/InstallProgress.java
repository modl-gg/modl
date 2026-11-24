package gg.modl.backend.update.install;

import org.jspecify.annotations.Nullable;
import java.util.concurrent.atomic.AtomicReference;

final class InstallProgress {
    private final AtomicReference<InstallState> state = new AtomicReference<>(InstallState.IDLE);

    InstallState current() {
        return state.get();
    }

    boolean begin(String version) {
        InstallState current = state.get();
        return !current.busy() && state.compareAndSet(current, new InstallState(InstallStage.DOWNLOADING, version, null, 0));
    }

    void downloaded(int percent) {
        state.updateAndGet(current -> current.stage() == InstallStage.DOWNLOADING
            ? new InstallState(InstallStage.DOWNLOADING, current.targetVersion(), null, percent)
            : current);
    }

    void stage(InstallStage stage) {
        state.updateAndGet(current -> new InstallState(stage, current.targetVersion(), null, current.downloadPercent()));
    }

    void failed(@Nullable InstallProblem problem) {
        state.updateAndGet(current -> new InstallState(InstallStage.FAILED, current.targetVersion(),
            problem == null ? InstallProblem.INTERNAL : problem, current.downloadPercent()));
    }
}
