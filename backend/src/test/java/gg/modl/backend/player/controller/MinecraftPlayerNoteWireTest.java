package gg.modl.backend.player.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.dto.request.MinecraftCreatePlayerNoteRequest;
import gg.modl.backend.player.dto.response.CreateNoteResult;
import gg.modl.backend.player.service.MinecraftPlayerService;
import gg.modl.backend.player.service.PlayerLookupService;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.CreatePlayerNoteRequest;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class MinecraftPlayerNoteWireTest {
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";
    private static final String STAFF = "staff-1";

    private enum Route { V1, V3 }

    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final MinecraftPlayerService players = mock(MinecraftPlayerService.class);
    private final MinecraftPlayerController v1 = new MinecraftPlayerController(players, mock(PlayerLookupService.class));
    private final MinecraftPlayerV3Controller v3 = new MinecraftPlayerV3Controller(players, mock(PlayerLookupService.class));
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    static Stream<Arguments> everyRouteAndKind() {
        return Stream.of(Route.values())
            .flatMap(route -> Stream.of(PlayerNoteKind.values()).map(kind -> Arguments.of(route, kind)));
    }

    @ParameterizedTest
    @MethodSource("everyRouteAndKind")
    void bothRoutesHandTheKindAndTheNamedStaffMemberToTheService(Route route, PlayerNoteKind kind) {
        acceptNotes();

        send(route, kind, STAFF);

        verify(players).createNote(eq(server), eq(PLAYER), eq(kind), eq("text"), eq("Mod"),
            argThat(issuer -> STAFF.equals(issuer.staffId())));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void bothRoutesHandTheConsoleMarkerToTheService(Route route) {
        acceptNotes();

        send(route, PlayerNoteKind.WARNING, null);

        verify(players).createNote(eq(server), eq(PLAYER), eq(PlayerNoteKind.WARNING), eq("text"), eq("Mod"),
            argThat(MinecraftIssuer::isConsole));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aRequestWithoutAKindIsRefused(Route route) {
        assertThatThrownBy(() -> send(route, null, STAFF))
            .isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(PlayerNoteKind.KIND_REQUIRED));
        verifyNoInteractions(players);
    }

    @Test
    void theJsonRouteReadsTheKindThePluginSends() {
        MinecraftCreatePlayerNoteRequest request = JsonMapper.builder().build().readValue(
            "{\"text\":\"spam\",\"issuerId\":\"staff-1\",\"kind\":\"WARNING\"}", MinecraftCreatePlayerNoteRequest.class);

        assertThat(request.kind()).isEqualTo(PlayerNoteKind.WARNING);
    }

    private void acceptNotes() {
        when(players.createNote(any(), any(), any(), any(), any(), any())).thenReturn(new CreateNoteResult.Created("Note added"));
    }

    private void send(Route route, @Nullable PlayerNoteKind kind, @Nullable String staffId) {
        http.setAttribute(RequestAttribute.SERVER, server);
        if (route == Route.V1) {
            v1.createPlayerNote(PLAYER, new MinecraftCreatePlayerNoteRequest("text", "Mod", staffId, staffId == null, kind), http);
            return;
        }
        CreatePlayerNoteRequest.Builder request = CreatePlayerNoteRequest.newBuilder().setText("text").setIssuerName("Mod");
        if (staffId != null) {
            request.setIssuerId(staffId);
        } else {
            request.setConsole(true);
        }
        if (kind != null) {
            request.setKind(PlayerNoteKindProto.toProto(kind));
        }
        v3.createPlayerNote(PLAYER, request.build(), http);
    }
}
