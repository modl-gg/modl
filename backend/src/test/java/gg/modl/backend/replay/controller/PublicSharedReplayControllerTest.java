package gg.modl.backend.replay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.service.ReplayFile;
import gg.modl.backend.replay.share.ReplayShareService;
import gg.modl.backend.replay.share.SharedReplay;
import gg.modl.backend.support.TestServers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

class PublicSharedReplayControllerTest {
    private static final String TOKEN = "a".repeat(43);
    private static final String BASE = RESTMappingV1.PUBLIC_SHARED_REPLAYS + "/" + TOKEN;

    private ReplayShareService shares;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        shares = mock(ReplayShareService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicSharedReplayController(shares, new ClientIpResolver()))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .setMessageConverters(new ProtoJsonHttpMessageConverter(), new ProtoBinaryHttpMessageConverter(),
                new ResourceHttpMessageConverter(), new ResourceRegionHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
            .build();
    }

    @Test
    void theMetadataNamesOnlyWhatTheViewerShowsAndCountsTheViewersNetwork() throws Exception {
        ReplayDocument replay = new ReplayDocument();
        replay.setId("r1");
        replay.setTargetName("Target");
        replay.setMcVersion("26.1.2");
        replay.setFormatVersion(5);
        replay.setFileSize(10);
        replay.setTargetUuid("00000000-0000-0000-0000-000000000001");
        replay.setCreatedAt(Instant.parse("2026-11-24T10:00:00Z"));
        when(shares.open(any(), eq(TOKEN), eq("20010db800000000::/64")))
            .thenReturn(Optional.of(new SharedReplay(replay, "Example Network", BASE + "/file")));

        mockMvc.perform(get(BASE).accept(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr("2001:db8::1234");
                    return request;
                })
                .requestAttr(RequestAttribute.SERVER, TestServers.server("server", "owner@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targetName").value("Target"))
            .andExpect(jsonPath("$.serverName").value("Example Network"))
            .andExpect(jsonPath("$.fileUrl").value(BASE + "/file"))
            .andExpect(jsonPath("$.replayId").doesNotExist())
            .andExpect(jsonPath("$.targetUuid").doesNotExist());
    }

    @Test
    void theFileIsAPureReadThatServesRanges() throws Exception {
        ReplayFile file = ReplayFiles.served("0123456789");
        when(shares.openFile(TOKEN)).thenReturn(Optional.of(file));

        mockMvc.perform(get(BASE + "/file").header(HttpHeaders.RANGE, "bytes=2-5"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/10"))
            .andExpect(content().bytes("2345".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(get(BASE + "/file")).andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"));

        verify(shares, times(2)).openFile(TOKEN);
        verify(shares, never()).open(any(), any(), any());
    }

    @Test
    void anUnknownLinkIsNotFoundWithOneCode() throws Exception {
        when(shares.open(any(), any(), any())).thenReturn(Optional.empty());
        when(shares.openFile(any())).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE).accept(MediaType.APPLICATION_JSON)
                .requestAttr(RequestAttribute.SERVER, TestServers.server("server", "owner@example.com")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_SHARE_NOT_FOUND"));
        mockMvc.perform(get(BASE + "/file"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPLAY_SHARE_NOT_FOUND"));
    }
}
