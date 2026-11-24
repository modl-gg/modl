package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.model.AiConnectionState.Readiness;
import org.jspecify.annotations.Nullable;

public record AiStatusView(boolean ready, Readiness readiness, @Nullable String providerName, @Nullable String model) {
}
