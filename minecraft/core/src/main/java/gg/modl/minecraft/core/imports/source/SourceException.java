package gg.modl.minecraft.core.imports.source;

import lombok.Getter;

@Getter
public final class SourceException extends Exception {
    private final ImportProblem problem;

    private SourceException(ImportProblem problem, String detail, Throwable cause) {
        super(detail, cause);
        this.problem = problem;
    }

    private SourceException(ImportProblem problem, String detail) {
        super(detail);
        this.problem = problem;
    }

    public static SourceException missing(ImportProblem problem, String detail) {
        return new SourceException(problem, detail);
    }

    public static SourceException missing(String detail) {
        return new SourceException(ImportProblem.SOURCE_MISSING, detail);
    }

    public static SourceException failed(Throwable cause) {
        return new SourceException(ImportProblem.SOURCE_UNREADABLE, cause.toString(), cause);
    }

    public static SourceException failed(String detail) {
        return new SourceException(ImportProblem.SOURCE_UNREADABLE, detail);
    }

    public static SourceException cancelled() {
        return new SourceException(ImportProblem.SOURCE_UNREADABLE, "Cancelled");
    }

    public static SourceException noSpace(String detail) {
        return new SourceException(ImportProblem.NO_SPACE, detail);
    }

    public static SourceException tooLarge(String detail) {
        return new SourceException(ImportProblem.TOO_MANY_PLAYERS, detail);
    }

    public static SourceException locked(String detail) {
        return new SourceException(ImportProblem.SOURCE_LOCKED, detail);
    }

    public String getMessageKey() {
        return problem.messageKey();
    }
}
