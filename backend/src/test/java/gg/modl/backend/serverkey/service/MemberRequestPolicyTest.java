package gg.modl.backend.serverkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.ticket.dto.request.MinecraftCreateTicketRequest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MemberRequestPolicyTest {
    private static final String PLAYER = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String OTHER = "1f8fad5b-d9cb-469f-a165-70867728950e";

    private final ReplayRepository replays = mock(ReplayRepository.class);
    private final MemberRequestPolicy policy = new MemberRequestPolicy(replays);

    @Test
    void anAnticheatReportFromAMemberIsStampedWithTheKeyName() {
        MinecraftCreateTicketRequest admitted = policy.admitTicket(member(), ticket("player", PLAYER, PLAYER, "spoofed"));

        assertThat(admitted.createdServer()).isEqualTo("lobby");
        assertThat(admitted.reportedPlayerUuid()).isEqualTo(PLAYER);
    }

    @Test
    void aMemberCannotFileAnyOtherTicket() {
        for (MinecraftCreateTicketRequest refused : List.of(
            ticket("player", PLAYER, OTHER, null),
            ticket("support", PLAYER, PLAYER, null),
            ticket("appeal", PLAYER, PLAYER, null),
            ticket("player", PLAYER, null, null))) {
            assertThatThrownBy(() -> policy.admitTicket(member(), refused))
                .isInstanceOf(ForbiddenException.class)
                .extracting(failure -> ((BaseApplicationException) failure).getMachineCode()).isEqualTo(ServerKeyErrors.SCOPE);
        }
    }

    @Test
    void aMemberReportCarriesTheAutoReporterPriorityAndNoTagsOrChat() {
        MinecraftCreateTicketRequest submitted = new MinecraftCreateTicketRequest(PLAYER, "Grim", "player", "Cheating", "Detected",
            PLAYER, "Steve", List.of("forged chat"), List.of("pinned"), "critical", null, null);

        MinecraftCreateTicketRequest admitted = policy.admitTicket(member(), submitted);

        assertThat(admitted.priority()).isEqualTo("normal");
        assertThat(admitted.tags()).isEmpty();
        assertThat(admitted.chatMessages()).isNull();
    }

    @Test
    void aMemberMayAttachOnlyAReplayOfTheReportedPlayer() {
        when(replays.findById("replay-own")).thenReturn(Optional.of(replayOf(PLAYER.toUpperCase(Locale.ROOT))));
        when(replays.findById("replay-other")).thenReturn(Optional.of(replayOf(OTHER)));

        assertThat(policy.admitTicket(member(), withReplay("replay-own")).replayUrl()).isEqualTo("replay-own");
        for (String refused : List.of("replay-other", "replay-missing")) {
            assertThatThrownBy(() -> policy.admitTicket(member(), withReplay(refused)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(MemberRequestPolicy.REPLAY_REFUSED);
        }
    }

    @Test
    void aFullCredentialIsUntouched() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttribute.MINECRAFT_CREDENTIAL, MinecraftCredential.instanceKey());
        MinecraftCreateTicketRequest ticket = ticket("support", PLAYER, OTHER, "Network");

        assertThat(policy.admitTicket(request, ticket)).isSameAs(ticket);
        assertThat(policy.admitTicket(new MockHttpServletRequest(), ticket)).isSameAs(ticket);
    }

    private static MockHttpServletRequest member() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttribute.MINECRAFT_CREDENTIAL,
            new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby"));
        return request;
    }

    private static MinecraftCreateTicketRequest withReplay(String replayId) {
        return new MinecraftCreateTicketRequest(PLAYER, "Grim", "player", "Cheating", "Detected", PLAYER, "Steve", null,
            List.of(), "normal", null, replayId);
    }

    private static ReplayDocument replayOf(String targetUuid) {
        ReplayDocument replay = new ReplayDocument();
        replay.setTargetUuid(targetUuid);
        return replay;
    }

    private static MinecraftCreateTicketRequest ticket(String type, String creator, String reported, String createdServer) {
        return new MinecraftCreateTicketRequest(creator, "Grim", type, "Cheating", "Detected", reported, "Steve", null,
            List.of(), "normal", createdServer, null);
    }
}
