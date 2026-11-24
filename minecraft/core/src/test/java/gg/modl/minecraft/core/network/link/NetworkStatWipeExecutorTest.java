package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.service.sync.StatWipeOutcome;
import gg.modl.proto.modl.v1.NetworkMember;
import gg.modl.proto.modl.v1.NetworkRosterEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ServerRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStatWipeExecutorTest {
    private final NetworkRoster roster = new NetworkRoster();
    private final List<RealtimeEnvelope> sent = new ArrayList<>();
    private final List<StatWipeOutcome> outcomes = new ArrayList<>();

    @Test
    void withMembersTheWipeIsHandedToThemAndNotAcknowledgedHere() {
        roster.replace(roster("lobby"));
        NetworkStatWipeExecutor executor = new NetworkStatWipeExecutor(roster, sent::add);

        executor.executeStatWipe("Steve", "0f8fad5b-d9cb-469f-a165-70867728950e", "p1", (outcome, server) -> outcomes.add(outcome));

        assertEquals(1, sent.size());
        assertEquals("p1", sent.get(0).getStatWipePush().getStatWipes(0).getPunishmentId());
        assertEquals(StatWipeOutcome.DELEGATED, outcomes.get(0));
    }

    @Test
    void withoutMembersNothingIsSentAndTheWipeIsRetriedAtTheNextLogin() {
        NetworkStatWipeExecutor executor = new NetworkStatWipeExecutor(roster, sent::add);

        executor.executeStatWipe("Steve", "uuid", "p1", (outcome, server) -> outcomes.add(outcome));

        assertTrue(sent.isEmpty());
        assertEquals(StatWipeOutcome.FAILED, outcomes.get(0));
    }

    @Test
    void aSendThatFailsGivesFailedSoTheWipeIsRetriedAtTheNextLogin() {
        roster.replace(roster("lobby"));
        NetworkStatWipeExecutor executor = new NetworkStatWipeExecutor(roster, envelope -> false);

        executor.executeStatWipe("Steve", "uuid", "p1", (outcome, server) -> outcomes.add(outcome));

        assertEquals(StatWipeOutcome.FAILED, outcomes.get(0));
    }

    static NetworkRosterEvent roster(String... names) {
        NetworkRosterEvent.Builder roster = NetworkRosterEvent.newBuilder();
        for (String name : names) {
            roster.addMembers(NetworkMember.newBuilder().setName(name).setRole(ServerRole.SERVER_ROLE_MEMBER));
        }
        return roster.build();
    }
}
