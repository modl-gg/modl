package gg.modl.minecraft.core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchableNetworkAvailabilityTest {
    private static final NetworkAvailability LOBBY_ONLY = "lobby"::equals;

    @Test
    void aProxyWithoutTheHubEnforcesNothingOnItsMembers() {
        SwitchableNetworkAvailability availability = new SwitchableNetworkAvailability(NetworkAvailability.NOWHERE);

        assertFalse(availability.isEnforcedOn("lobby"));
    }

    @Test
    void aStandaloneServerEnforcesEverythingItself() {
        SwitchableNetworkAvailability availability = new SwitchableNetworkAvailability(NetworkAvailability.EVERYWHERE);

        assertTrue(availability.isEnforcedOn("lobby"));
    }

    @Test
    void theLinkedRosterDecidesUntilItIsRemoved() {
        SwitchableNetworkAvailability availability = new SwitchableNetworkAvailability(NetworkAvailability.NOWHERE);

        Runnable undo = availability.use(LOBBY_ONLY);

        assertTrue(availability.isEnforcedOn("lobby"));
        assertFalse(availability.isEnforcedOn("survival"));
        undo.run();
        assertFalse(availability.isEnforcedOn("lobby"));
    }

    @Test
    void undoingAReplacedRosterKeepsTheNewerOne() {
        SwitchableNetworkAvailability availability = new SwitchableNetworkAvailability(NetworkAvailability.NOWHERE);
        Runnable undoFirst = availability.use(LOBBY_ONLY);
        availability.use(NetworkAvailability.EVERYWHERE);

        undoFirst.run();

        assertTrue(availability.isEnforcedOn("survival"));
    }
}
