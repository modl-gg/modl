package gg.modl.backend.player.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.player.dto.response.PlayerStandingView;
import gg.modl.backend.player.dto.response.PlayerStandingView.Entry;
import gg.modl.backend.player.dto.response.PlayerStandingView.State;
import gg.modl.backend.player.service.PlayerStandingService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.backend.support.TestServers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

class MinecraftPlayerStandingControllerTest {
    private static final String UUID_TEXT = "11111111-2222-3333-4444-555555555555";
    private static final String PATH = RESTMappingV1.MINECRAFT_PLAYERS + "/" + UUID_TEXT + "/standing";

    private final Server server = TestServers.server("server", "admin@example.com");
    private final PlayerStandingService standings = mock(PlayerStandingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MinecraftPlayerStandingController(standings))
        .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
        .build();

    @Test
    void theBodyHasOnlyTheFieldsTheMenuShows() throws Exception {
        when(standings.standingOf(server, UUID_TEXT)).thenReturn(new PlayerStandingView("Low", "Medium", 1, 6,
            List.of(new Entry("ABCD1234", "Cheating", "BAN", 1L, 2L, State.ACTIVE, 3L, 4L, "Follow the rules"))));

        String body = mvc.perform(get(PATH).requestAttr(RequestAttribute.SERVER, server))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode standing = ApplicationJson.MAPPER.readTree(body);
        assertThat(standing.propertyNames())
            .containsExactlyInAnyOrder("socialStatus", "gameplayStatus", "socialPoints", "gameplayPoints", "punishments");
        assertThat(standing.get("punishments").get(0).propertyNames()).containsExactlyInAnyOrder(
            "id", "type", "category", "issuedAt", "durationMs", "state", "endedAt", "expiresAt", "reason");
    }

    @Test
    void anUnknownPlayerAnswers404() throws Exception {
        when(standings.standingOf(server, UUID_TEXT)).thenThrow(new ResourceNotFoundException("Player not found"));

        mvc.perform(get(PATH).requestAttr(RequestAttribute.SERVER, server)).andExpect(status().isNotFound());
    }
}
