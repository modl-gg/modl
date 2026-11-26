package gg.modl.backend.replay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.replay.data.ReplayShare;
import gg.modl.backend.replay.share.CreatedReplayShare;
import gg.modl.backend.replay.share.ReplayShareExpiry;
import gg.modl.backend.replay.share.ReplayShareService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PanelReplayShareControllerTest {
    private static final String SHARES = RESTMappingV1.PANEL_REPLAYS + "/r1/shares";
    private static final String STAFF_EMAIL = "staff@example.com";
    private static final Instant CREATED = Instant.parse("2026-11-24T10:00:00Z");
    private static final ReplayShare OPEN = new ReplayShare("s1", "r1", "hash", "Moderator", CREATED, null, null, 3L,
        CREATED.plusSeconds(60));

    private final Server server = TestServers.server("server", "owner@example.com");
    private ReplayShareService shares;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        shares = mock(ReplayShareService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PanelReplayShareController(shares))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .setMessageConverters(new ProtoJsonHttpMessageConverter(), new ProtoBinaryHttpMessageConverter(),
                new JacksonJsonHttpMessageConverter())
            .build();
    }

    @Test
    void creatingALinkReturnsItsTokenOnceWithoutTheStoredHash() throws Exception {
        when(shares.create(server, STAFF_EMAIL, "r1", ReplayShareExpiry.NEVER))
            .thenReturn(new CreatedReplayShare(OPEN, "token", "https://panel.example/replay/shared/token"));

        mockMvc.perform(signedIn(post(SHARES)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiry\":\"REPLAY_SHARE_EXPIRY_NEVER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("token"))
            .andExpect(jsonPath("$.url").value("https://panel.example/replay/shared/token"))
            .andExpect(jsonPath("$.share.id").value("s1"))
            .andExpect(jsonPath("$.share.viewCount").value("3"))
            .andExpect(jsonPath("$.share.expiresAt").doesNotExist())
            .andExpect(jsonPath("$.share.tokenHash").doesNotExist());
    }

    @Test
    void aLinkWithoutAnExpiryChoiceIsRefused() throws Exception {
        mockMvc.perform(signedIn(post(SHARES)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(shares);
    }

    @Test
    void theLinksOfAReplayAreListed() throws Exception {
        when(shares.list("r1")).thenReturn(List.of(OPEN));

        mockMvc.perform(signedIn(get(SHARES)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shares[0].id").value("s1"))
            .andExpect(jsonPath("$.shares[0].createdBy").value("Moderator"));
    }

    @Test
    void revokingALinkAnswersWithTheRemainingList() throws Exception {
        ReplayShare revoked = new ReplayShare("s1", "r1", "hash", "Moderator", CREATED, null, CREATED.plusSeconds(120), 3L, null);
        when(shares.revoke(server, STAFF_EMAIL, "r1", "s1")).thenReturn(List.of(revoked));

        mockMvc.perform(signedIn(delete(SHARES + "/s1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shares[0].revokedAt").exists());
    }

    @Test
    void anUnknownReplayIsNotFoundWithItsCode() throws Exception {
        when(shares.list(eq("missing"))).thenThrow(new ResourceNotFoundException("Replay not found", ReplayShareService.REPLAY_NOT_FOUND));
        when(shares.revoke(any(), any(), eq("r1"), eq("missing")))
            .thenThrow(new ResourceNotFoundException("Share link not found", ReplayShareService.SHARE_NOT_FOUND));

        mockMvc.perform(signedIn(get(RESTMappingV1.PANEL_REPLAYS + "/missing/shares")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_NOT_FOUND"));
        mockMvc.perform(signedIn(delete(SHARES + "/missing")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_SHARE_NOT_FOUND"));
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        AuthSessionData session = new AuthSessionData();
        session.setEmail(STAFF_EMAIL);
        return request.accept(MediaType.APPLICATION_JSON)
            .requestAttr(RequestAttribute.SERVER, server)
            .requestAttr(RequestAttribute.SESSION, session);
    }
}
