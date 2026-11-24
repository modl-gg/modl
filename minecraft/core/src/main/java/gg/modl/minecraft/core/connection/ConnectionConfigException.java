package gg.modl.minecraft.core.connection;

public final class ConnectionConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String problem;

    public ConnectionConfigException(String problem) {
        super(problem);
        this.problem = problem;
    }

    public ConnectionConfigException(String problem, Throwable cause) {
        super(problem, cause);
        this.problem = problem;
    }

    @Override
    public String getMessage() {
        return problem;
    }
}
