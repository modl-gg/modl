package gg.modl.backend.appeal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.appeal.service.AppealService;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.PublicRecordVerificationService;
import gg.modl.backend.ticket.service.TicketAccessKeys;
import gg.modl.backend.ticket.service.TicketEmailVerificationService;
import gg.modl.backend.ticket.service.TicketPublicLinks;
import gg.modl.backend.ticket.service.TicketResponseAssembler;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicAppealControllerTest {
    private static final String ACCESS_KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

    private AppealService appealService;
    private TicketEmailVerificationService verificationService;
    private TicketResponseAssembler assembler;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        appealService = mock(AppealService.class);
        verificationService = mock(TicketEmailVerificationService.class);
        RealtimeEventPublisher realtimeEventPublisher = mock(RealtimeEventPublisher.class);
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.ticket(any(), any())).thenAnswer(invocation ->
            "https://panel.example.com/ticket/" + invocation.getArgument(0) + "?key=" + invocation.getArgument(1));
        assembler = new TicketResponseAssembler(mock(StaffRepository.class), new TicketPublicLinks(publicUrls));
        PublicRecordAccessService recordAccessService = new PublicRecordAccessService(
            new TicketAccessKeys(new IdGenerator()), verificationService, () -> true);
        PublicRecordVerificationService recordVerificationService =
            new PublicRecordVerificationService(verificationService, () -> true);
        server = TestServers.server("Demo", "admin@example.com");

        mockMvc = MockMvcBuilders
            .standaloneSetup(new PublicAppealController(appealService, recordAccessService, recordVerificationService, realtimeEventPublisher,
                new ClientIpResolver()))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .setMessageConverters(new ProtoJsonHttpMessageConverter(), new ProtoBinaryHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    private Ticket appealWithContactEmail() {
        return Ticket.builder()
            .id("APPEAL-1")
            .type(TicketCategory.APPEAL)
            .subject("Appeal for Punishment: ban-1")
            .status(TicketStatus.OPEN)
            .created(Instant.ofEpochMilli(1_700_000_000_000L))
            .publicAccessKey(ACCESS_KEY)
            .data(Map.of("contactEmail", "player@example.com"))
            .build();
    }

    @Test
    void getAppealWithoutTokenRequiresVerificationWhenContactEmailPresent() throws Exception {
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appealWithContactEmail()));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.requiresVerification").value(true))
            .andExpect(jsonPath("$.ticketId").value("APPEAL-1"));
    }

    @Test
    void getAppealWithValidTokenReturnsAppeal() throws Exception {
        Ticket appeal = appealWithContactEmail();
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appeal));
        when(verificationService.validateToken(server, "APPEAL-1", "valid-token")).thenReturn(true);
        TicketResponse appealResponse = assembler.toTicketResponse(server, appeal);
        when(appealService.toResponse(server, appeal)).thenReturn(appealResponse);

        mockMvc.perform(get(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1")
                .param("token", "valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("APPEAL-1"))
            .andExpect(jsonPath("$.subject").value("Appeal for Punishment: ban-1"));
    }

    @Test
    void getAppealWithTheAccessKeyReturnsAppeal() throws Exception {
        Ticket appeal = appealWithContactEmail();
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appeal));
        TicketResponse appealResponse = assembler.toTicketResponse(server, appeal);
        when(appealService.toResponse(server, appeal)).thenReturn(appealResponse);

        mockMvc.perform(get(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1")
                .param("token", ACCESS_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("APPEAL-1"))
            .andExpect(jsonPath("$.publicUrl").doesNotExist());
    }

    @Test
    void getAppealWithoutContactEmailStillRequiresTheAccessKey() throws Exception {
        Ticket appeal = Ticket.builder()
            .id("APPEAL-1")
            .type(TicketCategory.APPEAL)
            .publicAccessKey(ACCESS_KEY)
            .data(Map.of())
            .build();
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appeal));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.requiresVerification").value(true));
    }

    @Test
    void createAppealReturnsTheKeyedPublicLink() throws Exception {
        Ticket appeal = appealWithContactEmail();
        TicketResponse appealResponse = assembler.toTicketResponse(server, appeal);
        when(appealService.createAppeal(eq(server), any())).thenReturn(appealResponse);

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"punishmentId\":\"ban-1\",\"playerUuid\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"email\":\"\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.appealId").value("APPEAL-1"))
            .andExpect(jsonPath("$.publicUrl").value("https://panel.example.com/ticket/APPEAL-1?key=" + ACCESS_KEY));
    }

    @Test
    void addReplyToLockedAppealReturnsConflictWithMessage() throws Exception {
        Ticket appeal = appealWithContactEmail();
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appeal));
        when(verificationService.validateToken(server, "APPEAL-1", "valid-token")).thenReturn(true);
        when(appealService.addPublicReply(eq(server), eq("APPEAL-1"), eq("hello"), isNull()))
            .thenThrow(new ConflictException("Appeal is locked and cannot accept new replies"));

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1/replies")
                .param("token", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Appeal is locked and cannot accept new replies"));
    }

    @Test
    void requestVerificationSendsCodeAndReturnsEmailHint() throws Exception {
        Ticket appeal = appealWithContactEmail();
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appeal));
        when(verificationService.sendVerificationCode(server, appeal)).thenReturn("p***@example.com");

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1/request-verification"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.emailHint").value("p***@example.com"));
    }

    @Test
    void requestVerificationReturnsNotFoundForMissingAppeal() throws Exception {
        when(appealService.getAppealRaw(server, "APPEAL-404")).thenReturn(Optional.empty());

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-404/request-verification"))
            .andExpect(status().isNotFound());
    }

    @Test
    void requestVerificationRejectsAppealWithoutContactEmail() throws Exception {
        Ticket appeal = Ticket.builder()
            .id("APPEAL-1")
            .type(TicketCategory.APPEAL)
            .data(Map.of())
            .build();
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appeal));

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1/request-verification"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verifyCodeReturnsTokenForValidCode() throws Exception {
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appealWithContactEmail()));
        when(verificationService.verifyCode(eq(server), eq("APPEAL-1"), eq("123456"), anyString())).thenReturn("issued-token");

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.token").value("issued-token"));
    }

    @Test
    void verifyCodeRejectsInvalidCode() throws Exception {
        when(appealService.getAppealRaw(server, "APPEAL-1")).thenReturn(Optional.of(appealWithContactEmail()));
        when(verificationService.verifyCode(eq(server), eq("APPEAL-1"), eq("000000"), anyString())).thenReturn(null);

        mockMvc.perform(post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
            .andExpect(status().isForbidden());
    }
}
