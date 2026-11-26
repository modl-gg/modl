package gg.modl.backend.replay.controller;

import gg.modl.backend.support.ApplicationJson;
import gg.modl.backend.support.TestServers;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.ErrorResponseDTO;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.service.ReplayConfirmation;
import gg.modl.backend.replay.service.ReplayConfirmationService;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftReplayControllerTest {
    private ReplayService replayService;
    private ReplayConfirmationService confirmations;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        replayService = mock(ReplayService.class);
        confirmations = mock(ReplayConfirmationService.class);
        server = TestServers.server("Demo", "admin@example.com");

        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftReplayController(replayService, confirmations))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .defaultRequest(post("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void v1UploadReturnsJsonShapePluginParses() throws Exception {
        when(replayService.initUpload(same(server), eq("1.21.4"), eq(2048L), isNull(), isNull()))
            .thenReturn(new InitReplayUploadResponse(
                "replay-1",
                "https://storage.example/upload",
                "PUT",
                Map.of("Content-Type", "application/octet-stream")
            ));

        mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mcVersion": "1.21.4",
                      "fileSize": 2048
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayId").exists())
            .andExpect(jsonPath("$.uploadUrl").exists())
            .andExpect(jsonPath("$.method").exists())
            .andExpect(jsonPath("$.requiredHeaders").exists());
    }

    @Test
    void v1UploadInvalidRequestStillReturnsJsonError() throws Exception {
        MvcResult result = mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "mcVersion": "",
                      "fileSize": 0
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn();

        ErrorResponseDTO error = ApplicationJson.MAPPER
            .readValue(result.getResponse().getContentAsByteArray(), ErrorResponseDTO.class);
        assertEquals(400, error.status());
        assertTrue(error.error().startsWith("Invalid data provided"));
        assertFalse(result.getResponse().getContentType().contains(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE));
    }

    @Test
    void v1ConfirmReturnsJsonContract() throws Exception {
        when(confirmations.confirmUpload(same(server), eq("replay-1"))).thenReturn(ReplayConfirmation.CONFIRMED);

        mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/confirm/replay-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Upload confirmed"));

        when(confirmations.confirmUpload(same(server), eq("replay-1"))).thenReturn(ReplayConfirmation.FILE_INVALID);

        mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/confirm/replay-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REPLAY_FILE_INVALID"));

        when(confirmations.confirmUpload(same(server), eq("replay-1"))).thenReturn(ReplayConfirmation.NOT_FOUND);

        mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/confirm/replay-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_NOT_FOUND"));
    }

    @Test
    void aConfirmThatCannotBeCheckedNowAsksThePluginToRetry() throws Exception {
        when(confirmations.confirmUpload(same(server), eq("replay-1"))).thenReturn(ReplayConfirmation.TRY_AGAIN);

        mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/confirm/replay-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
            .andExpect(jsonPath("$.code").value("REPLAY_VALIDATION_UNAVAILABLE"));

        when(confirmations.confirmUpload(same(server), eq("replay-1"))).thenReturn(ReplayConfirmation.ALREADY_FAILED);

        mockMvc.perform(post(RESTMappingV1.MINECRAFT_REPLAYS + "/confirm/replay-1")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REPLAY_UPLOAD_FAILED"));
    }
}
