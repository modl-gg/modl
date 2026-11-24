package gg.modl.backend.realtime.rate;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.realtime.auth.RealtimePrincipal;
import gg.modl.backend.realtime.rate.PluginInboundBudget.Verdict;
import gg.modl.backend.realtime.state.RealtimeConnectionState;
import gg.modl.backend.realtime.state.RealtimeOutbox;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.ServerRole;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginInboundBudgetTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
    private final PluginInboundBudget budget = new PluginInboundBudget(clock);

    @Test
    void aMemberMaySendItsBurstThenDropsWithOneWarningPerSecond() {
        RealtimeConnectionState member = connection(ServerRole.SERVER_ROLE_MEMBER);
        for (int index = 0; index < PluginInboundBudget.MEMBER.capacity(); index++) {
            assertThat(budget.admit(member)).isEqualTo(Verdict.ADMIT);
        }

        assertThat(budget.admit(member)).isEqualTo(Verdict.DROP_AND_WARN);
        assertThat(budget.admit(member)).isEqualTo(Verdict.DROP_SILENTLY);
        clock.advance(Duration.ofMillis(10));
        assertThat(budget.admit(member)).isEqualTo(Verdict.DROP_SILENTLY);
    }

    @Test
    void tenSecondsOfContinuousExhaustionCloseTheConnectionEvenWithTrickledRefills() {
        RealtimeConnectionState member = connection(ServerRole.SERVER_ROLE_MEMBER);
        for (int index = 0; index < PluginInboundBudget.MEMBER.capacity(); index++) {
            budget.admit(member);
        }
        List<Verdict> verdicts = new ArrayList<>();
        for (int tick = 0; tick < 1_100; tick++) {
            clock.advance(Duration.ofMillis(10));
            verdicts.add(budget.admit(member));
            verdicts.add(budget.admit(member));
        }

        assertThat(verdicts).contains(Verdict.ADMIT).endsWith(Verdict.CLOSE);
    }

    @Test
    void aPauseResetsTheExhaustion() {
        RealtimeConnectionState member = connection(ServerRole.SERVER_ROLE_MEMBER);
        for (int index = 0; index <= PluginInboundBudget.MEMBER.capacity(); index++) {
            budget.admit(member);
        }
        clock.advance(Duration.ofSeconds(9));
        assertThat(budget.admit(member)).isEqualTo(Verdict.ADMIT);
        clock.advance(Duration.ofSeconds(9));

        assertThat(budget.admit(member)).isEqualTo(Verdict.ADMIT);
    }

    @Test
    void aCoreHasTheLargerAllowance() {
        RealtimeConnectionState core = connection(ServerRole.SERVER_ROLE_CORE);
        for (int index = 0; index < PluginInboundBudget.CORE.capacity(); index++) {
            assertThat(budget.admit(core)).isEqualTo(Verdict.ADMIT);
        }
        assertThat(budget.admit(core)).isEqualTo(Verdict.DROP_AND_WARN);
    }

    private static RealtimeConnectionState connection(ServerRole role) {
        RealtimeConnectionState state = new RealtimeConnectionState(new RealtimeOutbox(10, 65_536));
        MinecraftCredential credential = role == ServerRole.SERVER_ROLE_MEMBER
            ? new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby")
            : MinecraftCredential.instanceKey();
        state.authenticate(RealtimePrincipal.minecraft(TestServers.server("Server", "owner@example.com"), "instance",
            credential, role, "lobby", "3.0.0"));
        return state;
    }
}
