package gg.modl.backend.update.install;

import org.jspecify.annotations.Nullable;

final class InstallFailedException extends Exception {
    private static final long serialVersionUID = 1L;

    private final InstallProblem problem;

    InstallFailedException(InstallProblem problem, @Nullable String message, Throwable cause) {
        super(message, cause);
        this.problem = problem;
    }

    InstallFailedException(InstallProblem problem, String message) {
        super(message);
        this.problem = problem;
    }

    InstallProblem problem() {
        return problem;
    }
}
