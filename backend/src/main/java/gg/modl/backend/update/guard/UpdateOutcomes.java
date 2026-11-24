package gg.modl.backend.update.guard;

import gg.modl.update.UpdateTransaction;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class UpdateOutcomes {
    private volatile @Nullable RolledBack rolledBack;

    void rolledBack(UpdateTransaction transaction, boolean restored) {
        rolledBack = new RolledBack(transaction, restored);
    }

    public Optional<RolledBack> takeRolledBack() {
        RolledBack taken = rolledBack;
        rolledBack = null;
        return Optional.ofNullable(taken);
    }

    public record RolledBack(UpdateTransaction transaction, boolean restored) {
    }
}
