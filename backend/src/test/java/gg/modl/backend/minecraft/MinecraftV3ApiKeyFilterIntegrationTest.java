package gg.modl.backend.minecraft;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.dashboard.controller.MinecraftDashboardV3Controller;
import gg.modl.backend.dashboard.dto.response.MinecraftDashboardStatsResponse;
import gg.modl.backend.dashboard.service.DashboardService;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.filter.ApiKeyFilter;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.ratelimit.FrozenTimeBuckets;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import gg.modl.backend.infrastructure.ratelimit.RateLimitGate;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.MinecraftCredentialVerifier;
import gg.modl.backend.serverkey.service.ServerKeyCache;
import gg.modl.backend.serverkey.service.ServerKeyLastSeen;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.ApiError;
import gg.modl.proto.modl.v1.MinecraftDashboardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MinecraftV3ApiKeyFilterIntegrationTest {
    private static final String VALID_API_KEY = "valid-api-key";
    private static final String INVALID_API_KEY = "invalid-api-key";
    private static final String GUESSED_SERVER_KEY = "msk_" + "a".repeat(43);

    private InstanceContext instanceContext;
    private DashboardService dashboardService;
    private MockMvc mockMvc;
    private Server server;
    private ServerKeyCache serverKeys;

    @BeforeEach
    void setUp() {
        instanceContext = mock(InstanceContext.class);
        dashboardService = mock(DashboardService.class);
        server = TestServers.server("Demo", "admin@example.com");
        server.setApiKey(VALID_API_KEY);
        when(instanceContext.instance()).thenReturn(server);

        serverKeys = mock(ServerKeyCache.class);
        MinecraftCredentialVerifier verifier = new MinecraftCredentialVerifier(
            instanceContext, new InstanceProperties(null, null, null, null, null, null), serverKeys,
            mock(ServerKeyLastSeen.class));
        RateLimitGate gate = new RateLimitGate(new RateLimitConfig(FrozenTimeBuckets.pool()), new ClientIpResolver(),
            new ProtobufErrorResponseWriter());
        ApiKeyFilter apiKeyFilter = new ApiKeyFilter(verifier, instanceContext, new ProtobufErrorResponseWriter(), gate);
        mockMvc = MockMvcBuilders.standaloneSetup(new MinecraftDashboardV3Controller(dashboardService))
            .addFilters(apiKeyFilter)
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoBinaryHttpMessageConverter(), new ProtoJsonHttpMessageConverter())
            .build();
    }

    @Test
    void v3MinecraftRouteWithoutApiKeyReturnsBinaryUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(401, error.getStatusCode());
        assertEquals("UNAUTHENTICATED", error.getCode());
        verifyNoInteractions(dashboardService);
    }

    @Test
    void v3MinecraftRouteWithInvalidApiKeyReturnsBinaryUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .header(RequestHeader.API_KEY, INVALID_API_KEY)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        ApiError error = ApiError.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(401, error.getStatusCode());
        assertEquals("UNAUTHENTICATED", error.getCode());
        verifyNoInteractions(dashboardService);
    }

    @Test
    void anAddressThatKeepsFailingIsRefusedBeforeAnyKeyLookup() throws Exception {
        int allowedFailures = RateLimitTier.MINECRAFT_FAILED_CREDENTIAL.getCapacity();
        for (int attempt = 0; attempt < allowedFailures; attempt++) {
            mockMvc.perform(statsWithKey(GUESSED_SERVER_KEY)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(statsWithKey(GUESSED_SERVER_KEY))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        verify(serverKeys, times(allowedFailures)).findByHash(anyString());
        verifyNoInteractions(dashboardService);
    }

    @Test
    void validKeysAreNeverChargedAgainstTheFailureBudget() throws Exception {
        when(dashboardService.getMinecraftStats(server)).thenReturn(new MinecraftDashboardStatsResponse(0, 0, 0, 0, 0, 0, 0, 0));
        for (int attempt = 0; attempt <= RateLimitTier.MINECRAFT_FAILED_CREDENTIAL.getCapacity(); attempt++) {
            mockMvc.perform(statsWithKey(VALID_API_KEY)).andExpect(status().isOk());
        }

        mockMvc.perform(statsWithKey(INVALID_API_KEY)).andExpect(status().isUnauthorized());
    }

    @Test
    void v3MinecraftRouteWithValidApiKeyUsesAuthenticatedServer() throws Exception {
        MinecraftDashboardStatsResponse stats = new MinecraftDashboardStatsResponse(
            11,
            12,
            13,
            14,
            15,
            16,
            31,
            99
        );
        when(dashboardService.getMinecraftStats(server)).thenReturn(stats);

        MvcResult result = mockMvc.perform(get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
                .header(RequestHeader.API_KEY, VALID_API_KEY)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(ProtobufMediaTypes.APPLICATION_X_PROTOBUF))
            .andReturn();

        MinecraftDashboardResponse response = MinecraftDashboardResponse.parseFrom(result.getResponse().getContentAsByteArray());
        assertEquals(200, response.getStatus());
        assertEquals(99, response.getStats().getTotalPlayers());
        verify(dashboardService).getMinecraftStats(same(server));
    }

    private static MockHttpServletRequestBuilder statsWithKey(String apiKey) {
        return get(RESTMappingV3.PREFIX_MINECRAFT + "/dashboard/stats")
            .header(RequestHeader.API_KEY, apiKey)
            .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF);
    }
}
