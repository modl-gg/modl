package gg.modl.backend.punishment.controller;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.protobuf.Struct;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.player.dto.response.AppealEligibility;
import gg.modl.backend.player.dto.response.AppealInfoView;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.proto.modl.v1.PublicPunishmentAppealInfoResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

@ExtendWith(MockitoExtension.class)
class PublicPunishmentControllerTest {

    @Mock
    private PunishmentQueryService punishmentQueryService;

    @Mock
    private HttpServletRequest request;

    private PublicPunishmentController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicPunishmentController(punishmentQueryService);
    }

    @Test
    void getAppealInfoUsesWorkflowStatusForExistingAppeals() {
        Server server = TestServers.server("server", "admin@example.com");

        Map<String, Object> existingAppealData = new java.util.HashMap<>();
        existingAppealData.put("status", "rejected");
        existingAppealData.put("appealWorkflowStatus", "rejected");
        AppealInfoView info = new AppealInfoView(
            "punishment-1", null, null, null, false, false, null, existingAppealData, null);

        when(request.getAttribute(RequestAttribute.SERVER)).thenReturn(server);
        when(punishmentQueryService.getPublicPunishmentWithAppealEligibility(server, "punishment-1"))
            .thenReturn(Optional.of(new AppealEligibility.Eligible(info)));

        ResponseEntity<?> response = controller.getAppealInfo("punishment-1", request);

        assertEquals(200, response.getStatusCode().value());
        PublicPunishmentAppealInfoResponse body = (PublicPunishmentAppealInfoResponse) response.getBody();
        Struct existingAppeal = body.getExistingAppeal();
        assertEquals("rejected", existingAppeal.getFieldsOrThrow("status").getStringValue());
        assertEquals("rejected", existingAppeal.getFieldsOrThrow("appealWorkflowStatus").getStringValue());
    }

    @Test
    void getAppealInfoOfAnUnstartedPunishmentKeepsTheBadRequestAndAddsTheMachineCode() throws Exception {
        Server server = TestServers.server("server", "admin@example.com");
        String message = "This punishment has not been started yet and cannot be appealed at this time.";
        when(punishmentQueryService.getPublicPunishmentWithAppealEligibility(any(), any()))
            .thenReturn(Optional.of(new AppealEligibility.NotStarted(message)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .build();

        String body = mockMvc.perform(get(RESTMappingV1.PUBLIC_PUNISHMENT + "/punishment-1/appeal-info")
                .requestAttr(RequestAttribute.SERVER, server))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();

        JsonNode error = ApplicationJson.MAPPER.readTree(body);
        assertEquals(400, error.get("status").asInt());
        assertEquals(message, error.get("error").asString());
        assertEquals(message, error.get("message").asString());
        assertEquals("PUNISHMENT_NOT_STARTED", error.get("code").asString());
    }
}
