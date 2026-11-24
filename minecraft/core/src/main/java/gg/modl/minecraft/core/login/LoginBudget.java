package gg.modl.minecraft.core.login;

import java.time.Duration;

public final class LoginBudget {
    public static final long SECONDS = 10;
    public static final Duration DURATION = Duration.ofSeconds(SECONDS);

    private LoginBudget() {
    }
}
