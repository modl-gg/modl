package gg.modl.backend.update.commit;

import gg.modl.backend.infrastructure.scheduling.InstanceReadiness;
import gg.modl.update.UpdateTransaction;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class UpdateGate implements InstanceReadiness {
    private volatile @Nullable UpdateTransaction holding;
    private volatile boolean instanceCommitted;

    public void hold(UpdateTransaction transaction) {
        holding = transaction;
    }

    public Optional<UpdateTransaction> holding() {
        return Optional.ofNullable(holding);
    }

    public boolean closed() {
        return holding != null;
    }

    @Override
    public boolean committed() {
        return instanceCommitted;
    }

    void open() {
        holding = null;
    }

    void markInstanceCommitted() {
        instanceCommitted = true;
    }
}
