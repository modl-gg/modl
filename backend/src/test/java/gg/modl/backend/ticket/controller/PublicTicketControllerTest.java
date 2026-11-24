package gg.modl.backend.ticket.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.web.ClientIpResolver;
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
import gg.modl.backend.ticket.data.Ticket.ChatMessage;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.PublicRecordVerificationService;
import gg.modl.backend.ticket.service.TicketAccessKeys;
import gg.modl.backend.ticket.service.TicketEmailVerificationService;
import gg.modl.backend.ticket.service.TicketPublicLinks;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketResponseAssembler;
import gg.modl.backend.ticket.service.TicketService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicTicketControllerTest {
    private static final String ACCESS_KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    private static final Instant CREATED = Instant.ofEpochMilli(1_700_000_000_000L);

    private TicketService ticketService;
    private TicketEmailVerificationService verificationService;
    private TicketResponseAssembler assembler;
    private MockMvc mockMvc;
    private Server server;

    @BeforeEach
    void setUp() {
        ticketService = mock(TicketService.class);
        TicketReplyService ticketReplyService = mock(TicketReplyService.class);
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
            .standaloneSetup(new PublicTicketController(ticketService, ticketReplyService, recordAccessService, recordVerificationService,
                realtimeEventPublisher, new ClientIpResolver()))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()))
            .setMessageConverters(new ProtoJsonHttpMessageConverter(), new ProtoBinaryHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    @Test
    void createTicketReturnsTheKeyedPublicLink() throws Exception {
        Ticket created = ticket().status(TicketStatus.OPEN).build();
        TicketResponse createdResponse = assembler.toTicketResponse(server, created);
        when(ticketService.createTicket(eq(server), any(CreateTicketRequest.class))).thenReturn(createdResponse);

        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"support\",\"subject\":\"Need help\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ticketId").value("TICKET-1"))
            .andExpect(jsonPath("$.publicUrl").value("https://panel.example.com/ticket/TICKET-1?key=" + ACCESS_KEY));
    }

    @Test
    void publicCreationIgnoresFieldsOnlyStaffOrTheGameMaySet() throws Exception {
        Ticket created = ticket().status(TicketStatus.OPEN).build();
        TicketResponse createdResponse = assembler.toTicketResponse(server, created);
        when(ticketService.createTicket(eq(server), any(CreateTicketRequest.class))).thenReturn(createdResponse);

        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"player","subject":"Report","description":"details","creatorName":"Reporter",
                     "creatorEmail":"reporter@example.com","creatorUuid":"0f8fad5b-d9cb-469f-a165-70867728950e",
                     "reportedPlayerUuid":"11111111-2222-3333-4444-555555555555","reportedPlayerName":"Victim",
                     "chatMessages":[{"username":"Victim","message":"forged"}],"tags":["vip"],"priority":"critical",
                     "formData":{"rule":"griefing"},"creatorIdentifier":"browser-1","emailAuthEnabled":true,
                     "fieldLabels":{"rule":"Rule"}}
                    """))
            .andExpect(status().isCreated());

        ArgumentCaptor<CreateTicketRequest> captor = ArgumentCaptor.forClass(CreateTicketRequest.class);
        verify(ticketService).createTicket(eq(server), captor.capture());
        CreateTicketRequest mapped = captor.getValue();
        assertEquals("player", mapped.type());
        assertEquals("Report", mapped.subject());
        assertEquals("details", mapped.description());
        assertEquals("Reporter", mapped.creatorName());
        assertEquals("reporter@example.com", mapped.creatorEmail());
        assertEquals(Map.of("rule", "griefing"), mapped.formData());
        assertEquals("browser-1", mapped.creatorIdentifier());
        assertEquals(true, mapped.emailAuthEnabled());
        assertEquals(Map.of("rule", "Rule"), mapped.fieldLabels());
        assertNull(mapped.creatorUuid());
        assertNull(mapped.reportedPlayerUuid());
        assertNull(mapped.reportedPlayerName());
        assertNull(mapped.chatMessages());
        assertNull(mapped.tags());
        assertNull(mapped.priority());
    }

    @Test
    void bareTicketIdIsNeverEnoughToReadATicket() throws Exception {
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket().build()));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.requiresVerification").value(true))
            .andExpect(jsonPath("$.ticketId").value("TICKET-1"))
            .andExpect(jsonPath("$.message").value("Open the private link you received for this record to continue"));
        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/status"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.requiresVerification").value(true));
        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/replies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.requiresVerification").value(true));

        verifyNoInteractions(verificationService);
    }

    @Test
    void staffNotesAreNotPartOfThePublicTicketOrAppeal() throws Exception {
        for (TicketCategory category : List.of(TicketCategory.SUPPORT, TicketCategory.APPEAL)) {
            Ticket withNote = ticket().type(category).status(TicketStatus.OPEN)
                .replies(List.of(
                    TicketReply.builder().id("1").name("Player").content("my question").type("user").created(CREATED).build()))
                .notes(List.of(TicketNote.builder().text("informant is Alex").issuerName("Mod").date(CREATED).build()))
                .build();
            TicketResponse response = assembler.toTicketResponse(server, withNote);
            when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(withNote));
            when(ticketService.toResponse(server, withNote)).thenReturn(response);
            when(ticketService.getPublicFormFieldIds(server, withNote)).thenReturn(Set.of());

            mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1").param("token", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies.length()").value(1))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.replies[0].content").value("my question"))
                .andExpect(jsonPath("$.messages[0].content").value("my question"))
                .andExpect(jsonPath("$.notes").isEmpty());
        }
    }

    @Test
    void wrongAccessKeyIsRejected() throws Exception {
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket().build()));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/status")
                .param("token", ACCESS_KEY.substring(1)))
            .andExpect(status().isForbidden());
    }

    @Test
    void ticketStatusIsReadableWithTheAccessKey() throws Exception {
        Ticket ticket = ticket().subject("Need help").status(TicketStatus.OPEN).build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        TicketResponse ticketResponse = assembler.toTicketResponse(server, ticket);
        when(ticketService.toResponse(server, ticket)).thenReturn(ticketResponse);

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/status")
                .param("token", ACCESS_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("TICKET-1"))
            .andExpect(jsonPath("$.subject").value("Need help"));
    }

    @Test
    void ticketStatusIsReadableWithAnEmailVerificationToken() throws Exception {
        Ticket ticket = ticket().subject("Need help").emailAuthEnabled(true).build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        when(verificationService.validateToken(server, "TICKET-1", "valid-token")).thenReturn(true);
        TicketResponse ticketResponse = assembler.toTicketResponse(server, ticket);
        when(ticketService.toResponse(server, ticket)).thenReturn(ticketResponse);

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/status")
                .param("token", "valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("TICKET-1"));
    }

    @Test
    void verificationRequirementHintsTheContactEmailWhenDeliveryIsOn() throws Exception {
        Ticket ticket = ticket().data(Map.of("creatorEmail", "player@example.com")).build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.emailHint").value("p***@example.com"))
            .andExpect(jsonPath("$.message").value(
                "Open the private link you received for this record, or verify your email, to continue"));
    }

    @Test
    void publicTicketRedactsPrivateDataAndNeverEchoesTheKey() throws Exception {
        Ticket ticket = ticket()
            .subject("Need help")
            .creatorName("PlayerOne")
            .creatorUuid("uuid-1")
            .reportedPlayer("Reported")
            .reportedPlayerUuid("uuid-2")
            .emailAuthEnabled(true)
            .replies(List.of(TicketReply.builder().id("reply-1").content("hello").staff(false).creatorIdentifier("browser-secret").build()))
            .tags(List.of("tag"))
            .formData(Map.of(
                "description", "Submitted form",
                "email", "player@example.com",
                "contact_email", "legacy@example.com",
                "creatorEmail", "player@example.com",
                "creatorIdentifier", "browser-secret",
                "emailAuthEnabled", true,
                "contactEmail", "appeal@example.com",
                "playerUuid", "uuid-appeal"
            ))
            .data(Map.of(
                "creatorEmail", "player@example.com",
                "creatorIdentifier", "browser-secret",
                "emailAuthEnabled", true,
                "contactEmail", "appeal@example.com",
                "email", "player@example.com",
                "contact_email", "legacy@example.com",
                "playerUuid", "uuid-appeal"
            ))
            .chatMessages(List.of(ChatMessage.builder().content("message").timestamp(CREATED).build()))
            .build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        TicketResponse ticketResponse = assembler.toTicketResponse(server, ticket);
        when(ticketService.toResponse(server, ticket)).thenReturn(ticketResponse);
        when(ticketService.getPublicFormFieldIds(server, ticket)).thenReturn(Set.of("description"));
        when(verificationService.validateToken(server, "TICKET-1", "valid-token")).thenReturn(true);

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1")
                .param("token", "valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replies[0].content").value("hello"))
            .andExpect(jsonPath("$.messages[0].content").value("hello"))
            .andExpect(jsonPath("$.formData.description").value("Submitted form"))
            .andExpect(jsonPath("$.formData.creatorEmail").doesNotExist())
            .andExpect(jsonPath("$.formData.creatorIdentifier").doesNotExist())
            .andExpect(jsonPath("$.formData.emailAuthEnabled").doesNotExist())
            .andExpect(jsonPath("$.formData.contactEmail").doesNotExist())
            .andExpect(jsonPath("$.formData.email").doesNotExist())
            .andExpect(jsonPath("$.formData.contact_email").doesNotExist())
            .andExpect(jsonPath("$.formData.playerUuid").doesNotExist())
            .andExpect(jsonPath("$.data.creatorEmail").doesNotExist())
            .andExpect(jsonPath("$.data.creatorIdentifier").doesNotExist())
            .andExpect(jsonPath("$.data.emailAuthEnabled").doesNotExist())
            .andExpect(jsonPath("$.data.contactEmail").doesNotExist())
            .andExpect(jsonPath("$.data.email").doesNotExist())
            .andExpect(jsonPath("$.data.contact_email").doesNotExist())
            .andExpect(jsonPath("$.data.playerUuid").doesNotExist())
            .andExpect(jsonPath("$.replies[0].creatorIdentifier").value(""))
            .andExpect(jsonPath("$.creatorUuid").value(""))
            .andExpect(jsonPath("$.reportedPlayer").value("Reported"))
            .andExpect(jsonPath("$.reportedPlayerUuid").value(""))
            .andExpect(jsonPath("$.chatMessages[0].content").value("message"))
            .andExpect(jsonPath("$.publicUrl").doesNotExist())
            .andExpect(jsonPath("$.publicAccessKey").doesNotExist());
    }

    @Test
    void publicTicketHidesRawChatMessagesWhenTicketDoesNotRequireEmailVerification() throws Exception {
        Ticket ticket = ticket()
            .emailAuthEnabled(false)
            .chatMessages(List.of(ChatMessage.builder().content("private chat line").timestamp(CREATED).build()))
            .build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        TicketResponse ticketResponse = assembler.toTicketResponse(server, ticket);
        when(ticketService.toResponse(server, ticket)).thenReturn(ticketResponse);

        mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1")
                .param("token", ACCESS_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.chatMessages[0]").doesNotExist());
    }

    @Test
    void submitTicketFormWithTheAccessKeyIsNotTreatedAsEmailVerification() throws Exception {
        Ticket ticket = ticket().emailAuthEnabled(true).data(Map.of()).build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        TicketResponse submitted = assembler.toTicketResponse(server, ticket);
        when(ticketService.submitTicketForm(server, "TICKET-1",
            new SubmitTicketFormRequest("Need help", null, null, null, null, null, null), false))
            .thenReturn(submitted);

        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/submit")
                .param("token", ACCESS_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Need help\"}"))
            .andExpect(status().isOk());

        verify(verificationService, never()).validateToken(any(), any(), any());
    }

    @Test
    void submitTicketFormWithoutKeyOrTokenIsForbidden() throws Exception {
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket().data(Map.of()).build()));

        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Need help\"}"))
            .andExpect(status().isForbidden());

        verify(ticketService, never()).submitTicketForm(any(), any(), any(), anyBoolean());
    }

    @Test
    void anOptedInTicketIsNotReadableByLinkHolders() throws Exception {
        Ticket ticket = ticket().emailAuthEnabled(true).data(Map.of("creatorEmail", "player@example.com")).build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));

        for (String path : List.of("/TICKET-1", "/TICKET-1/status")) {
            mockMvc.perform(get(RESTMappingV1.PUBLIC_TICKETS + path).param("token", ACCESS_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.requiresVerification").value(true));
        }
        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/replies")
                .param("token", ACCESS_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
            .andExpect(status().isForbidden());
        verify(ticketService, never()).toResponse(any(), any());
    }

    @Test
    void theOptInReachesTheSubmission() throws Exception {
        Ticket ticket = ticket().status(TicketStatus.UNFINISHED).build();
        when(ticketService.getTicketRaw(server, "TICKET-1")).thenReturn(Optional.of(ticket));
        TicketResponse submitted = assembler.toTicketResponse(server, ticket);
        when(ticketService.submitTicketForm(server, "TICKET-1",
            new SubmitTicketFormRequest("Need help", "player@example.com", null, null, null, null, true), false))
            .thenReturn(submitted);

        mockMvc.perform(post(RESTMappingV1.PUBLIC_TICKETS + "/TICKET-1/submit")
                .param("token", ACCESS_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Need help\",\"creatorEmail\":\"player@example.com\",\"emailAuthEnabled\":true}"))
            .andExpect(status().isOk());

        verify(ticketService).submitTicketForm(server, "TICKET-1",
            new SubmitTicketFormRequest("Need help", "player@example.com", null, null, null, null, true), false);
    }

    private static Ticket.TicketBuilder ticket() {
        return Ticket.builder()
            .id("TICKET-1")
            .type(TicketCategory.SUPPORT)
            .created(CREATED)
            .publicAccessKey(ACCESS_KEY);
    }
}
