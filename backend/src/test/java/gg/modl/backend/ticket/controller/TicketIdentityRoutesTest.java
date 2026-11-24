package gg.modl.backend.ticket.controller;

import static gg.modl.backend.ticket.TicketHarness.ACCESS_KEY;
import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.ai.service.AITicketAnalysisService;
import gg.modl.backend.appeal.controller.PanelAppealController;
import gg.modl.backend.appeal.controller.PublicAppealController;
import gg.modl.backend.appeal.service.AppealService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.exception.GlobalExceptionHandler;
import gg.modl.backend.infrastructure.proto.ProtoBinaryHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoJsonHttpMessageConverter;
import gg.modl.backend.infrastructure.proto.ProtoValidationAdvice;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RESTMappingV3;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.ApplicationJson;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.service.ContactEmailPolicy;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.PublicRecordVerificationService;
import gg.modl.backend.ticket.service.TicketAccessKeys;
import gg.modl.backend.ticket.service.TicketNoteVisibility;
import gg.modl.backend.ticket.service.TicketSearchService;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.serverkey.service.MemberRequestPolicy;
import gg.modl.proto.modl.v1.DismissReportRequest;
import gg.modl.proto.modl.v1.MinecraftCreateTicketRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TicketIdentityRoutesTest {
    private static final String SESSION_EMAIL = "mod@example.com";
    private static final String SESSION_NAME = "SessionMod";
    private static final String PUNISHMENT = "BANAAAAA";
    private static final String PLAYER_UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String SPOOFED_REPLY = """
        {"name":"Owner","content":"hello","type":"staff-note","staff":true,"action":"Comment",
         "avatar":"https://evil.example/owner.png","creatorIdentifier":"owner-browser"}""";
    private static final String SPOOFED_APPEAL_REPLY = """
        {"name":"Owner","content":"hello","type":"staff-note","staff":true,"avatar":"https://evil.example/owner.png"}""";

    private final Server server = TestServers.server("Demo", "admin@example.com");

    private final PunishmentModificationAuthorizer modificationAuthorizer = mock(PunishmentModificationAuthorizer.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void panelReplyTakesTheSessionIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            panel(harness, post(RESTMappingV1.PANEL_TICKETS + "/SUPPORT-1/replies"),
                SPOOFED_REPLY.replace("\"staff\":true", "\"staff\":false"), 201);

            assertSessionStaff(harness.stored("SUPPORT-1").getReplies().getLast());
        }
    }

    @SqlContractTest
    void panelNoteTakesTheSessionIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            panel(harness, post(RESTMappingV1.PANEL_TICKETS + "/SUPPORT-1/notes"),
                "{\"text\":\"note\",\"issuerName\":\"Owner\",\"issuerAvatar\":\"https://evil.example/owner.png\"}", 201);

            assertSessionIssuer(harness.stored("SUPPORT-1").getNotes().getLast());
        }
    }

    @SqlContractTest
    void panelUpdateTakesTheSessionIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            panel(harness, patch(RESTMappingV1.PANEL_TICKETS + "/SUPPORT-1"), "{\"newReply\":"
                + SPOOFED_REPLY.replace("\"staff\":true", "\"staff\":false")
                + ",\"newNote\":{\"text\":\"note\",\"issuerName\":\"Owner\",\"issuerAvatar\":\"https://evil.example/o.png\"}}", 200);

            assertSessionStaff(harness.stored("SUPPORT-1").getReplies().getLast());
            assertSessionIssuer(harness.stored("SUPPORT-1").getNotes().getLast());
        }
    }

    @SqlContractTest
    void panelQuickResponseTakesTheSessionIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            QuickResponseSettings settings = QuickResponseSettings.builder().build();
            when(harness.quickResponses().getQuickResponseSettings(server)).thenReturn(settings);
            when(harness.quickResponses().findAction(settings, "general", "ok"))
                .thenReturn(QuickResponseSettings.Action.builder().id("ok").name("Ok").message("Handled").build());

            panel(harness, post(RESTMappingV1.PANEL_TICKETS + "/SUPPORT-1/quick-response"),
                "{\"actionId\":\"ok\",\"categoryId\":\"general\",\"name\":\"Owner\"}", 200);

            assertSessionStaff(harness.stored("SUPPORT-1").getReplies().getLast());
        }
    }

    @SqlContractTest
    void panelAppealReplyTakesTheSessionIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            panel(harness, post(RESTMappingV1.PANEL_APPEALS + "/APPEAL-1/replies"),
                SPOOFED_APPEAL_REPLY.replace("\"staff\":true", "\"staff\":false"), 201);

            assertSessionStaff(harness.stored("APPEAL-1").getReplies().getLast());
        }
    }

    @SqlContractTest
    void panelAppealDecisionTakesTheSessionIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            panel(harness, patch(RESTMappingV1.PANEL_APPEALS + "/APPEAL-1/status"),
                "{\"status\":\"under_review\",\"staffUsername\":\"Owner\"}", 200);

            TicketReply statusChange = harness.stored("APPEAL-1").getReplies().getLast();
            assertSessionAuthor(statusChange);
            assertEquals("system", statusChange.getType());
        }
    }

    @SqlContractTest
    void publicTicketCreationCannotSetAStaffIdentity(DialectTarget target) throws Exception {
        for (String path : List.of("", "/unfinished")) {
            try (TicketHarness harness = seeded(target, dataDirectory.resolve(path.isEmpty() ? "create" : "draft"))) {
                String body = send(harness, post(RESTMappingV1.PUBLIC_TICKETS + path), """
                    {"type":"support","subject":"Help","description":"body","creatorName":"Player",
                     "name":"Owner","staff":true,"avatar":"https://evil.example/owner.png","action":"close"}""", 201);

                String id = ApplicationJson.MAPPER.readTree(body).get("ticketId").asString();
                assertPlayer(harness.stored(id).getReplies().getFirst(), "Player");
            }
        }
    }

    @SqlContractTest
    void publicSubmissionCannotSetAStaffIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            send(harness, post(RESTMappingV1.PUBLIC_TICKETS + "/DRAFT-1/submit").param("token", ACCESS_KEY), """
                {"subject":"Help","staff":true,"name":"Owner","formData":{"details":"text","staff":"true","name":"Owner"}}""", 200);

            assertPlayer(harness.stored("DRAFT-1").getReplies().getFirst(), "Player");
        }
    }

    @SqlContractTest
    void publicReplyCannotSetAStaffIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            send(harness, post(RESTMappingV1.PUBLIC_TICKETS + "/SUPPORT-1/replies").param("token", ACCESS_KEY),
                SPOOFED_REPLY, 201);

            assertPlayer(harness.stored("SUPPORT-1").getReplies().getLast(), "Player");
        }
    }

    @SqlContractTest
    void publicAppealCreationCannotSetAStaffIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            String body = send(harness, post(RESTMappingV1.PUBLIC_APPEALS),
                "{\"punishmentId\":\"" + PUNISHMENT + "\",\"playerUuid\":\"" + PLAYER_UUID
                    + "\",\"email\":\"\",\"reason\":\"please\",\"name\":\"Owner\",\"staff\":true}", 201);

            String id = ApplicationJson.MAPPER.readTree(body).get("appealId").asString();
            assertPlayer(harness.stored(id).getReplies().getFirst(), "Player");
        }
    }

    @SqlContractTest
    void publicAppealReplyCannotSetAStaffIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            send(harness, post(RESTMappingV1.PUBLIC_APPEALS + "/APPEAL-1/replies").param("token", ACCESS_KEY),
                SPOOFED_APPEAL_REPLY, 201);

            assertPlayer(harness.stored("APPEAL-1").getReplies().getLast(), "Player");
        }
    }

    @SqlContractTest
    void minecraftCreationKeepsThePlayerIdentityAndCannotSetTheStaffFlag(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            String body = send(harness, post(RESTMappingV1.MINECRAFT_TICKETS), "{\"creatorUuid\":\"" + PLAYER_UUID
                + "\",\"creatorName\":\"Player\",\"type\":\"support\",\"subject\":\"Help\",\"description\":\"body\",\"staff\":true}", 200);
            String id = ApplicationJson.MAPPER.readTree(body).get("ticketId").asString();

            assertPlayer(harness.stored(id).getReplies().getFirst(), "Player");
        }
    }

    @SqlContractTest
    void minecraftV3CreationKeepsThePlayerIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            MinecraftCreateTicketRequest request = MinecraftCreateTicketRequest.newBuilder()
                .setCreatorUuid(PLAYER_UUID).setCreatorName("Player").setType("bug").setDescription("body").build();

            sendProto(harness, post(RESTMappingV3.PREFIX_MINECRAFT + "/tickets"), request.toByteArray());

            List<Ticket> created = harness.tickets().findRecentByCreator(PLAYER_UUID, 10).stream()
                .filter(found -> found.getType() == TicketCategory.BUG)
                .toList();
            assertPlayer(harness.stored(created.getFirst().getId()).getReplies().getFirst(), "Player");
        }
    }

    @SqlContractTest
    void minecraftReportDecisionsKeepTheAuthenticatedPluginsIdentity(DialectTarget target) throws Exception {
        try (TicketHarness harness = seeded(target)) {
            send(harness, post(RESTMappingV1.MINECRAFT_REPORTS + "/PLAYER-1/dismiss"), "{\"dismissedBy\":\"GameMod\",\"console\":true}", 200);
            send(harness, post(RESTMappingV1.MINECRAFT_REPORTS + "/PLAYER-2/resolve"),
                "{\"resolvedBy\":\"GameMod\",\"punishmentId\":\"BANAAAAA\",\"console\":true}", 200);
            sendProto(harness, post(RESTMappingV3.PREFIX_MINECRAFT + "/reports/PLAYER-3/dismiss"),
                DismissReportRequest.newBuilder().setDismissedBy("GameMod").setConsole(true).build().toByteArray());

            for (String id : List.of("PLAYER-1", "PLAYER-2", "PLAYER-3")) {
                TicketReply reply = harness.stored(id).getReplies().getLast();
                assertEquals("GameMod", reply.getName());
                assertTrue(reply.isStaff());
            }
        }
    }

    private static void assertSessionStaff(TicketReply reply) {
        assertSessionAuthor(reply);
        assertEquals("staff", reply.getType());
    }

    private static void assertSessionAuthor(TicketReply reply) {
        assertEquals(SESSION_NAME, reply.getName());
        assertTrue(reply.isStaff());
        assertNull(reply.getAvatar());
        assertNull(reply.getCreatorIdentifier());
    }

    private static void assertSessionIssuer(TicketNote note) {
        assertEquals(SESSION_NAME, note.getIssuerName());
        assertNull(note.getIssuerAvatar());
    }

    private static void assertPlayer(TicketReply reply, String name) {
        assertEquals(name, reply.getName());
        assertFalse(reply.isStaff());
        assertNull(reply.getAvatar());
        assertNull(reply.getAction());
        assertTrue(List.of("user", "player").contains(reply.getType()));
    }

    private TicketHarness seeded(DialectTarget target) {
        return seeded(target, dataDirectory);
    }

    private TicketHarness seeded(DialectTarget target, Path directory) {
        TicketHarness harness = TicketHarness.start(target, directory);
        harness.insert(
            ticket("SUPPORT-1").creatorName("Player"),
            ticket("DRAFT-1").creatorName("Player").status(TicketStatus.UNFINISHED),
            ticket("APPEAL-1").type(TicketCategory.APPEAL).creatorName("Player"),
            ticket("PLAYER-1").type(TicketCategory.PLAYER),
            ticket("PLAYER-2").type(TicketCategory.PLAYER),
            ticket("PLAYER-3").type(TicketCategory.PLAYER));
        when(harness.staff().findByEmail(SESSION_EMAIL))
            .thenReturn(Optional.of(Staff.builder().id("staff-1").email(SESSION_EMAIL).username(SESSION_NAME).build()));
        return harness;
    }

    @SqlContractTest
    void theAppealStatusRouteChecksApprovalAgainstTheSession(DialectTarget target) throws Exception {
        try (TicketHarness harness = TicketHarness.start(target, dataDirectory)) {
            harness.insert(ticket("APPEAL-1").type(TicketCategory.APPEAL).appealWorkflowStatus(AppealWorkflowStatus.OPEN)
                .data(Map.of("punishmentId", PUNISHMENT, "playerUuid", PLAYER_UUID)));
            when(harness.staff().findByEmail(SESSION_EMAIL))
                .thenReturn(Optional.of(Staff.builder().id("staff-1").email(SESSION_EMAIL).username(SESSION_NAME).build()));
            doThrow(new ForbiddenException("denied")).when(modificationAuthorizer).requireAppealApproval(server, SESSION_EMAIL);

            panel(harness, patch(RESTMappingV1.PANEL_APPEALS + "/APPEAL-1/status"), "{\"status\":\"approved\"}", 403);

            verify(modificationAuthorizer).requireAppealApproval(server, SESSION_EMAIL);
            assertThat(harness.stored("APPEAL-1").getAppealWorkflowStatus()).isEqualTo(AppealWorkflowStatus.OPEN);
        }
    }

    private void panel(TicketHarness harness, MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        AuthSessionData session = new AuthSessionData();
        session.setEmail(SESSION_EMAIL);
        send(harness, request.requestAttr(RequestAttribute.SESSION, session), body, status);
    }

    private String send(TicketHarness harness, MockHttpServletRequestBuilder request, String body, int expectedStatus)
        throws Exception {
        return mockMvc(harness).perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus))
            .andReturn().getResponse().getContentAsString();
    }

    private void sendProto(TicketHarness harness, MockHttpServletRequestBuilder request, byte[] body) throws Exception {
        mockMvc(harness).perform(request
                .contentType(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .accept(ProtobufMediaTypes.APPLICATION_X_PROTOBUF)
                .content(body))
            .andExpect(status().isOk());
    }

    private MockMvc mockMvc(TicketHarness harness) {
        RealtimeEventPublisher realtime = mock(RealtimeEventPublisher.class);
        PanelActionAuditor auditor = mock(PanelActionAuditor.class);
        AITicketAnalysisService analysis = mock(AITicketAnalysisService.class);
        IssuerNameResolver identities = new IssuerNameResolver(harness.staff());
        PublicRecordAccessService access = new PublicRecordAccessService(
            new TicketAccessKeys(new IdGenerator()), harness.verificationService(), () -> false);
        PublicRecordVerificationService verification =
            new PublicRecordVerificationService(harness.verificationService(), () -> false);
        MinecraftTicketService minecraft = harness.minecraftService();
        AppealService appeals = appeals(harness);

        return MockMvcBuilders.standaloneSetup(
                new PanelTicketController(harness.ticketService(), new TicketSearchService(harness.tickets()),
                    harness.replyService(), harness.subscriptions(), realtime, auditor, identities, showingNotes()),
                new PanelAppealController(appeals, identities, realtime, auditor, showingNotes()),
                new PublicTicketController(harness.ticketService(), harness.replyService(), access, verification, realtime,
                    new ClientIpResolver()),
                new PublicAppealController(appeals, access, verification, realtime, new ClientIpResolver()),
                new MinecraftTicketsController(minecraft, analysis, new MemberRequestPolicy(mock(ReplayRepository.class))),
                new MinecraftTicketsV3Controller(minecraft, analysis, new MemberRequestPolicy(mock(ReplayRepository.class))),
                new MinecraftReportsController(minecraft),
                new MinecraftReportsV3Controller(minecraft))
            .setControllerAdvice(new GlobalExceptionHandler(new ProtobufErrorResponseWriter()), new ProtoValidationAdvice())
            .setMessageConverters(new ProtoJsonHttpMessageConverter(), new ProtoBinaryHttpMessageConverter(),
                new JacksonJsonHttpMessageConverter())
            .defaultRequest(get("/").requestAttr(RequestAttribute.SERVER, server))
            .build();
    }

    private AppealService appeals(TicketHarness harness) {
        Punishment punishment = new Punishment();
        punishment.setId(PUNISHMENT);
        punishment.setIssued(NOW);
        punishment.setStarted(NOW);
        punishment.setModifications(new ArrayList<>());
        punishment.replaceData(new HashMap<>(Map.of("altBlocking", false)));
        PunishmentRepository punishments = mock(PunishmentRepository.class);
        when(punishments.findById(PUNISHMENT))
            .thenReturn(Optional.of(new PunishmentRecord("player-1", PLAYER_UUID, "Player", punishment, 0)));
        return new AppealService(harness.tickets(), punishments, mock(PunishmentMutationService.class),
            mock(PunishmentLifecycleService.class), modificationAuthorizer, harness.inserter(), harness.announcer(), harness.transitions(),
            harness.content(), harness.messages(), harness.appender(), harness.uploads(), harness.assembler(),
            new ContactEmailPolicy(() -> false), appealableTypes(), harness.database().unitOfWork(), harness.database().clock());
    }

    private static PunishmentTypeService appealableTypes() {
        PunishmentTypeService types = mock(PunishmentTypeService.class);
        when(types.isAppealable(any(), anyInt())).thenReturn(true);
        return types;
    }

    private static TicketNoteVisibility showingNotes() {
        TicketNoteVisibility visibility = mock(TicketNoteVisibility.class);
        when(visibility.visibleTo(any(), any(), any(TicketResponse.class))).thenAnswer(call -> call.getArgument(2));
        when(visibility.visibleTo(any(), any(), anyList())).thenAnswer(call -> call.getArgument(2));
        return visibility;
    }
}
