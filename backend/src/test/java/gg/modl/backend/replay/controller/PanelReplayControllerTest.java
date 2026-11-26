package gg.modl.backend.replay.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.replay.dto.PanelReplayView;
import gg.modl.backend.replay.service.ReplayFile;
import gg.modl.backend.replay.service.ReplayService;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PanelReplayControllerTest {
    private ReplayService replayService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        replayService = mock(ReplayService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PanelReplayController(replayService, new ReplayProtoMapper()))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .setMessageConverters(new ProtoJsonHttpMessageConverter(), new ProtoBinaryHttpMessageConverter(),
                new ResourceHttpMessageConverter(), new ResourceRegionHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
            .build();
    }

    @Test
    void aStoredReplayAnswersWithItsFormatVersionAndFileUrl() throws Exception {
        when(replayService.getPanelReplay("r1")).thenReturn(Optional.of(new PanelReplayView("r1", "26.1.2", "COMPLETE", 2048L, 1000L,
            "00000000-0000-0000-0000-000000000001", "Target", false, "/v1/storage/objects/modl_instance/replays/x.modlreplay", 5)));

        mockMvc.perform(get(RESTMappingV1.PANEL_REPLAYS + "/r1").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayId").value("r1"))
            .andExpect(jsonPath("$.formatVersion").value(5))
            .andExpect(jsonPath("$.targetName").value("Target"))
            .andExpect(jsonPath("$.replayUrl").value("/v1/storage/objects/modl_instance/replays/x.modlreplay"));
    }

    @Test
    void theReplayFileStreamsWithRangesAndIsNeverCached() throws Exception {
        ReplayFile file = ReplayFiles.served("0123456789");
        when(replayService.completedFile("r1")).thenReturn(Optional.of(file));

        mockMvc.perform(get(RESTMappingV1.PANEL_REPLAYS + "/r1/file"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(content().bytes("0123456789".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(get(RESTMappingV1.PANEL_REPLAYS + "/r1/file").header(HttpHeaders.RANGE, "bytes=2-5"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
            .andExpect(content().bytes("2345".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMissingReplayFileIsNotFoundWithItsCode() throws Exception {
        when(replayService.completedFile("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get(RESTMappingV1.PANEL_REPLAYS + "/missing/file"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_NOT_FOUND"));
    }

    @Test
    void anUnknownReplayIsNotFoundWithItsCode() throws Exception {
        when(replayService.getPanelReplay("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get(RESTMappingV1.PANEL_REPLAYS + "/missing").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_NOT_FOUND"));
    }
}
