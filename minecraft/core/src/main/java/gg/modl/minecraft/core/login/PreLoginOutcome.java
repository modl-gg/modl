package gg.modl.minecraft.core.login;

import org.jspecify.annotations.Nullable;

public final class PreLoginOutcome {
    private static final PreLoginOutcome SKIPPED = new PreLoginOutcome(null);

    private final LoginService.@Nullable LoginResult result;

    private PreLoginOutcome(LoginService.@Nullable LoginResult result) {
        this.result = result;
    }

    public static PreLoginOutcome checked(LoginService.LoginResult result) {
        if (result == null) throw new IllegalArgumentException("a checked login needs its result");
        return new PreLoginOutcome(result);
    }

    public static PreLoginOutcome skipped() {
        return SKIPPED;
    }

    public boolean isSkipped() {
        return result == null;
    }

    public LoginService.LoginResult result() {
        LoginService.LoginResult checked = result;
        if (checked == null) throw new IllegalStateException("the login was not checked");
        return checked;
    }
}
