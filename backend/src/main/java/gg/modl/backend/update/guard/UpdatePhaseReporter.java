package gg.modl.backend.update.guard;

public interface UpdatePhaseReporter {
    UpdatePhaseReporter NONE = phase -> {
    };

    void report(String phase);
}
