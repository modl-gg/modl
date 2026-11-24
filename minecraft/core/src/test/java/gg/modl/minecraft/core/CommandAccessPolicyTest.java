package gg.modl.minecraft.core;

import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.command.PlayerOnly;
import gg.modl.minecraft.core.command.Staff2faGated;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.MapLocaleManager;
import org.junit.jupiter.api.Test;
import revxrsal.commands.annotation.list.AnnotationList;
import revxrsal.commands.exception.SendableException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandAccessPolicyTest {
    private final Cache emptyCache = new Cache(new CachedProfileRegistry());
    private static final CoreAvailability RUNNING = () -> CoreState.RUNNING;

    private final MapLocaleManager locale = new MapLocaleManager()
            .put("staff_2fa.not_verified", "Verify first")
            .put("api_errors.connection_failed", "Connecting, try again in a moment")
            .put("api_errors.startup_failed", "modl is not running");

    @Test
    void twoFactorGatedCommandsRequireVerificationEvenWhenTheStaffCacheIsStale() throws Exception {
        CommandAccessPolicy policy = new CommandAccessPolicy(new StaffActionGate(RUNNING, emptyCache, locale, new FixedStaff2fa(true, false), actor -> false));

        assertThrows(SendableException.class, () -> policy.enforce(gatedAnnotations(), player()));
    }

    @Test
    void verifiedPlayersPassTheTwoFactorGate() throws Exception {
        CommandAccessPolicy policy = new CommandAccessPolicy(new StaffActionGate(RUNNING, emptyCache, locale, new FixedStaff2fa(true, true), actor -> false));

        assertDoesNotThrow(() -> policy.enforce(gatedAnnotations(), player()));
    }

    @Test
    void theGateIsOpenWhenStaffTwoFactorIsDisabled() throws Exception {
        CommandAccessPolicy policy = new CommandAccessPolicy(new StaffActionGate(RUNNING, emptyCache, locale, new FixedStaff2fa(false, false), actor -> false));

        assertDoesNotThrow(() -> policy.enforce(gatedAnnotations(), player()));
    }

    @Test
    void aCoreThatIsStillStartingRefusesEveryCommandWithTheConnectingMessage() throws Exception {
        assertEquals("Connecting, try again in a moment", refusalWhile(CoreState.STARTING));
    }

    @Test
    void aCoreThatFailedOrStoppedRefusesEveryCommandWithTheNotRunningMessage() throws Exception {
        assertEquals("modl is not running", refusalWhile(CoreState.UNAVAILABLE));
    }

    @Test
    void theAvailabilityIsReadOnEveryCommandSoACoreThatBecomesReadyAcceptsCommands() throws Exception {
        AtomicReference<CoreState> state = new AtomicReference<>(CoreState.STARTING);
        CommandAccessPolicy policy = new CommandAccessPolicy(new StaffActionGate(state::get, emptyCache, locale, new FixedStaff2fa(false, false), actor -> false));
        assertThrows(SendableException.class, () -> policy.enforce(ungatedAnnotations(), player()));

        state.set(CoreState.RUNNING);

        assertDoesNotThrow(() -> policy.enforce(ungatedAnnotations(), player()));
    }

    @Test
    void theGateAlsoRefusesTheConsoleWhileTheCoreIsNotRunning() throws Exception {
        CommandAccessPolicy policy = new CommandAccessPolicy(new StaffActionGate(() -> CoreState.STARTING, emptyCache, locale,
                new FixedStaff2fa(false, false), actor -> false));

        assertThrows(SendableException.class,
                () -> policy.enforce(ungatedAnnotations(), new FakeCommandActor(null, "CONSOLE")));
    }

    private String refusalWhile(CoreState state) throws Exception {
        CommandAccessPolicy policy = new CommandAccessPolicy(new StaffActionGate(() -> state, emptyCache, locale, new FixedStaff2fa(false, false), actor -> false));
        return assertThrows(SendableException.class, () -> policy.enforce(ungatedAnnotations(), player())).getMessage();
    }

    @PlayerOnly
    @Staff2faGated
    @SuppressWarnings("unused")
    private void gatedCommand() {
    }

    @SuppressWarnings("unused")
    private void ungatedCommand() {
    }

    private AnnotationList ungatedAnnotations() throws NoSuchMethodException {
        return AnnotationList.create(CommandAccessPolicyTest.class.getDeclaredMethod("ungatedCommand"));
    }

    private AnnotationList gatedAnnotations() throws NoSuchMethodException {
        return AnnotationList.create(CommandAccessPolicyTest.class.getDeclaredMethod("gatedCommand"));
    }

    private static FakeCommandActor player() {
        return new FakeCommandActor(UUID.randomUUID(), "Player");
    }

    private static final class FixedStaff2fa extends Staff2faService {
        private final boolean enabled;
        private final boolean authenticated;

        private FixedStaff2fa(boolean enabled, boolean authenticated) {
            super(null, null);
            this.enabled = enabled;
            this.authenticated = authenticated;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isAuthenticated(UUID uuid) {
            return !enabled || authenticated;
        }
    }
}
