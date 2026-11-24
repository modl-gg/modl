package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.core.boot.CoreAvailability;
import gg.modl.minecraft.core.boot.CoreState;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.util.Permissions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffActionGateTest {
    private static final CoreAvailability RUNNING = () -> CoreState.RUNNING;
    private static final StaffRequirement TWO_FACTOR = StaffRequirement.builder().playerOnly(true).staff2fa(true).build();
    private static final StaffRequirement OPEN = StaffRequirement.builder().build();

    private final Cache cache = new Cache(new CachedProfileRegistry());
    private final MapLocaleManager locale = new MapLocaleManager()
            .put("staff_2fa.not_verified", "Verify first")
            .put("general.no_permission", "No permission")
            .put("general.players_only", "Players only")
            .put("api_errors.connection_failed", "Connecting, try again in a moment")
            .put("api_errors.startup_failed", "modl is not running");

    @Test
    void twoFactorGatedActionsRequireVerificationEvenWhenTheStaffCacheIsStale() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, new FixedStaff2fa(true, false), actor -> false);

        assertEquals("Verify first", denial(gate, player(), TWO_FACTOR));
    }

    @Test
    void verifiedPlayersPassTheTwoFactorGate() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, new FixedStaff2fa(true, true), actor -> false);

        assertDoesNotThrow(() -> gate.require(player(), TWO_FACTOR));
    }

    @Test
    void theGateIsOpenWhenStaffTwoFactorIsDisabled() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, new FixedStaff2fa(false, false), actor -> false);

        assertDoesNotThrow(() -> gate.require(player(), TWO_FACTOR));
    }

    @Test
    void aPlayerWithStaffPermissionsButNoStaffIdIsNeverTreatedAsStaff() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, null, actor -> false);
        StaffActor anonymous = player();
        StaffActor identified = StaffActors.of(new FakeCommandActor(UUID.randomUUID(), "Identified"));
        StaffRequirement pardon = StaffRequirement.builder().staffOnly(true).permission(Permissions.PUNISHMENT_MODIFY_PARDON).build();
        cache.cacheStaffPermissions(anonymous.uuid(), "Anonymous", null, "Helper",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_PARDON));
        cache.cacheStaffPermissions(identified.uuid(), "Identified", "staff-9", "Helper",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_PARDON));

        assertEquals("No permission", denial(gate, anonymous, pardon));
        assertDoesNotThrow(() -> gate.require(identified, pardon));
    }

    @Test
    void aCoreThatIsStillStartingRefusesWithTheConnectingMessage() {
        assertEquals("Connecting, try again in a moment",
                denial(new StaffActionGate(() -> CoreState.STARTING, cache, locale, null, actor -> false), player(), OPEN));
    }

    @Test
    void aCoreThatFailedOrStoppedRefusesWithTheNotRunningMessage() {
        assertEquals("modl is not running",
                denial(new StaffActionGate(() -> CoreState.UNAVAILABLE, cache, locale, null, actor -> false), player(), OPEN));
    }

    @Test
    void theAvailabilityIsReadOnEveryAction() {
        AtomicReference<CoreState> state = new AtomicReference<>(CoreState.STARTING);
        StaffActionGate gate = new StaffActionGate(state::get, cache, locale, null, actor -> false);
        assertThrows(StaffActionDenied.class, () -> gate.require(player(), OPEN));

        state.set(CoreState.RUNNING);

        assertDoesNotThrow(() -> gate.require(player(), OPEN));
    }

    @Test
    void theConsoleIsAlsoRefusedWhileTheCoreIsNotRunningButPassesEverythingElse() {
        StaffActor console = StaffActors.of(new FakeCommandActor(null, "CONSOLE"));
        StaffRequirement everything = StaffRequirement.builder().staffOnly(true).adminOnly(true)
                .permission(Permissions.MOD_ACTIONS).staff2fa(true).build();

        assertThrows(StaffActionDenied.class,
                () -> new StaffActionGate(() -> CoreState.STARTING, cache, locale, null, actor -> false).require(console, OPEN));
        assertDoesNotThrow(() -> new StaffActionGate(RUNNING, cache, locale, new FixedStaff2fa(true, false), actor -> false)
                .require(console, everything));
    }

    @Test
    void playerOnlyActionsRefuseTheConsole() {
        StaffActor console = StaffActors.of(new FakeCommandActor(null, "CONSOLE"));

        assertEquals("Players only", denial(new StaffActionGate(RUNNING, cache, locale, null, actor -> false), console, TWO_FACTOR));
    }

    @Test
    void staffAdminAndPermissionRequirementsAreChecked() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, null, actor -> false);
        StaffActor stranger = player();
        UUID moderatorUuid = UUID.randomUUID();
        cache.cacheStaffPermissions(moderatorUuid, "Mod", "mod", "Moderator",
                Collections.singletonList(Permissions.MOD_ACTIONS));
        StaffActor moderator = StaffActors.of(new FakeCommandActor(moderatorUuid, "Mod"));

        assertEquals("No permission", denial(gate, stranger, StaffRequirement.builder().staffOnly(true).build()));
        assertEquals("No permission",
                denial(gate, stranger, StaffRequirement.builder().permission(Permissions.MOD_ACTIONS).build()));
        assertEquals("No permission", denial(gate, moderator, StaffRequirement.builder().adminOnly(true).build()));
        assertDoesNotThrow(() -> gate.require(moderator,
                StaffRequirement.builder().staffOnly(true).permission(Permissions.MOD_ACTIONS).build()));
    }

    @Test
    void theAuthorizedIdentityIsTheStaffIdReadDuringAuthorization() throws StaffActionDenied {
        UUID staffUuid = UUID.randomUUID();
        Cache refreshedAfterAuthorization = new Cache(new CachedProfileRegistry()) {
            private int reads;

            @Override
            public String getStaffId(UUID playerUuid) {
                return reads++ == 0 ? super.getStaffId(playerUuid) : null;
            }
        };
        refreshedAfterAuthorization.cacheStaffPermissions(staffUuid, "Mod", "staff-7", "Moderator",
                Collections.singletonList(Permissions.PUNISHMENT_MODIFY_PARDON));
        StaffActionGate gate = new StaffActionGate(RUNNING, refreshedAfterAuthorization, locale, null, actor -> false);

        AuthorizedStaff issuer = gate.require(StaffActors.of(new FakeCommandActor(staffUuid, "Mod")),
                StaffRequirement.builder().staffOnly(true).permission(Permissions.PUNISHMENT_MODIFY_PARDON).build());

        assertEquals("staff-7", issuer.issuerId());
        assertNull(refreshedAfterAuthorization.getStaffId(staffUuid));
    }

    @Test
    void theConsoleIsAuthorizedWithoutAStaffIdAndAPlayerNeverIsSilently() throws StaffActionDenied {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, null, actor -> false);

        AuthorizedStaff console = gate.require(StaffActors.of(new FakeCommandActor(null, "CONSOLE")), OPEN);
        AuthorizedStaff player = gate.require(player(), OPEN);

        assertTrue(console.isConsole());
        assertNull(console.issuerId());
        assertThrows(IllegalStateException.class, player::issuerId);
    }

    @Test
    void viewOrModifyOrAnyApplyPermissionViewsPlayers() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, null, actor -> false);
        for (String held : new String[]{Permissions.PUNISHMENT_VIEW, Permissions.PUNISHMENT_MODIFY,
                Permissions.PUNISHMENT_APPLY_PREFIX + 7}) {
            assertDoesNotThrow(() -> gate.require(staffHolding("Helper", held), StaffRequirements.VIEW_PLAYERS), held);
        }
        assertDoesNotThrow(() -> gate.require(staffHolding("Super Admin", Permissions.TICKET_VIEW_ALL),
                StaffRequirements.VIEW_PLAYERS));
    }

    @Test
    void otherPermissionsDoNotViewPlayersButTheConsoleAlwaysDoes() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, null, actor -> false);
        for (String held : new String[]{Permissions.PLAYER_NOTE, Permissions.PLAYER_WARN, Permissions.TICKET_VIEW_ALL,
                Permissions.PUNISHMENT_MODIFY_PARDON, Permissions.MOD_ACTIONS}) {
            assertEquals("No permission", denial(gate, staffHolding("Helper", held), StaffRequirements.VIEW_PLAYERS), held);
        }
        assertEquals("No permission", denial(gate, player(), StaffRequirements.VIEW_PLAYERS));
        assertDoesNotThrow(() -> gate.require(StaffActors.of(new FakeCommandActor(null, "CONSOLE")),
                StaffRequirements.VIEW_PLAYERS));
    }

    @Test
    void notesAndWarningsAreIndependentOfEachOtherAndOfModifying() {
        StaffActionGate gate = new StaffActionGate(RUNNING, cache, locale, null, actor -> false);

        assertDoesNotThrow(() -> gate.require(staffHolding("Helper", Permissions.PLAYER_NOTE), StaffRequirements.ADD_PLAYER_NOTE));
        assertEquals("No permission",
                denial(gate, staffHolding("Helper", Permissions.PLAYER_WARN), StaffRequirements.ADD_PLAYER_NOTE));
        assertEquals("No permission",
                denial(gate, staffHolding("Helper", Permissions.PUNISHMENT_MODIFY), StaffRequirements.ADD_PLAYER_NOTE));
    }

    private StaffActor staffHolding(String role, String permission) {
        UUID uuid = UUID.randomUUID();
        cache.cacheStaffPermissions(uuid, "Staff", "staff-" + uuid, role, Collections.singletonList(permission));
        return StaffActors.of(new FakeCommandActor(uuid, "Staff"));
    }

    private static String denial(StaffActionGate gate, StaffActor actor, StaffRequirement requirement) {
        return assertThrows(StaffActionDenied.class, () -> gate.require(actor, requirement)).getMessage();
    }

    private static StaffActor player() {
        return StaffActors.of(new FakeCommandActor(UUID.randomUUID(), "Player"));
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
