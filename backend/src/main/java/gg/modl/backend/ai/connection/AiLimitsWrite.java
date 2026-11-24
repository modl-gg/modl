package gg.modl.backend.ai.connection;

import gg.modl.backend.ai.model.AiUsageLimits;

public record AiLimitsWrite(long expectedVersion, AiUsageLimits limits) {
}
