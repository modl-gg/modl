package gg.modl.backend.update.install;

import gg.modl.update.EmbeddedUpdateContract;
import java.util.Map;
import java.util.Optional;

public record EmbeddedPanelRestart(Runnable request) {
    public static Optional<EmbeddedPanelRestart> from(Map<String, Object> input) {
        return input.get(EmbeddedUpdateContract.PANEL_RESTART) instanceof Runnable restart
            ? Optional.of(new EmbeddedPanelRestart(restart))
            : Optional.empty();
    }
}
