package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.login.LoginPipeline;
import gg.modl.minecraft.core.login.LoginRefusalLog;
import gg.modl.minecraft.core.login.PreLoginOutcome;
import gg.modl.minecraft.core.util.PluginLogger;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@RequiredArgsConstructor
final class LoginAdmission {
    private final PluginLogger logger;
    private final LoginPipeline loginPipeline;

    @Nullable LoginDenial admit(@Nullable UUID uuid, String name, boolean allowedSoFar) {
        PreLoginOutcome outcome = uuid == null ? null : loginPipeline.getLoginCache().takePreLoginOutcome(uuid);
        if (!allowedSoFar) return null;
        try {
            return denialFor(outcome, name);
        } catch (RuntimeException failure) {
            return failedCheck(name, failure);
        }
    }

    LoginDenial failedCheck(String name, Exception failure) {
        logger.warning("The login check for " + name + " failed (" + failure + ") - blocking login for safety");
        return unverifiable();
    }

    private @Nullable LoginDenial denialFor(@Nullable PreLoginOutcome outcome, String name) {
        if (outcome == null) {
            logger.warning("No login check ran for " + name + " - blocking login for safety");
            return unverifiable();
        }
        if (outcome.isSkipped()) {
            logger.warning(name + " was refused before modl checked their bans, "
                    + "but another plugin allowed the login; modl denies it because their bans were not checked.");
            return LoginDenial.unchecked(loginPipeline.getLoginService().uncheckedLoginMessage());
        }
        String denialMessage = loginPipeline.getLoginService().denialMessage(outcome.result());
        if (denialMessage == null) return null;
        LoginRefusalLog.refused(logger, name, denialMessage);
        return LoginDenial.refused(denialMessage);
    }

    private LoginDenial unverifiable() {
        return LoginDenial.unchecked(loginPipeline.getLoginService().unverifiableBanStatusMessage());
    }
}
