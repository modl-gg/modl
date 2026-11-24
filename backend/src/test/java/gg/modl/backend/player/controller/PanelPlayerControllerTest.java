package gg.modl.backend.player.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.dto.request.AddEvidenceRequest;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.service.AccountLinkingService;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentQueryService;
import gg.modl.backend.replay.service.PlayerReplayListingService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.CreatePlayerNoteRequest;
import gg.modl.proto.modl.v1.ModifyPunishmentTicketsRequest;
import gg.modl.proto.modl.v1.PanelAddEvidenceRequest;
import gg.modl.proto.modl.v1.PanelAddModificationRequest;
import gg.modl.proto.modl.v1.PanelAddPunishmentNoteRequest;
import gg.modl.proto.modl.v1.PanelCreatePunishmentRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

class PanelPlayerControllerTest {
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String STAFF_EMAIL = "mod@example.com";
    private static final String PUNISHMENT = "PUNISH01";
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final PunishmentIssuer SESSION_STAFF = PunishmentIssuer.staff("RealMod", "staff-real");

    private final Server server = TestServers.server("server", OWNER_EMAIL);
    private final PlayerService players = mock(PlayerService.class);
    private final StaffRepository staff = mock(StaffRepository.class);
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final PunishmentEvidenceService evidence = mock(PunishmentEvidenceService.class);
    private final PunishmentMutationService mutations = mock(PunishmentMutationService.class);
    private final PanelPlayerController controller = new PanelPlayerController(players, new IssuerNameResolver(staff),
        mock(PunishmentQueryService.class), lifecycle, evidence, mutations, mock(AccountLinkingService.class),
        mock(PlayerReplayListingService.class), mock(PunishmentModificationAuthorizer.class));

    @BeforeEach
    void setUp() {
        when(staff.findByEmail(STAFF_EMAIL)).thenReturn(
            Optional.of(Staff.builder().id("staff-real").email(STAFF_EMAIL).username("RealMod").build()));
    }

    @Test
    void creationTakesTheIssuerFromTheSession() {
        controller.createPunishment(PLAYER.toString(), PanelCreatePunishmentRequest.newBuilder()
            .setTypeOrdinal(2).build(), sessionOf(STAFF_EMAIL));

        verify(lifecycle).createPanelPunishment(eq(server), eq(PLAYER), eq(SESSION_STAFF), any());
    }

    @Test
    void modificationsTakeTheIssuerFromTheSession() {
        controller.addModification(PLAYER.toString(), PUNISHMENT, PanelAddModificationRequest.newBuilder()
            .setType("MANUAL_PARDON").build(), sessionOf(STAFF_EMAIL));

        ArgumentCaptor<AddModificationRequest> sent = ArgumentCaptor.forClass(AddModificationRequest.class);
        verify(mutations).addModification(eq(server), eq(PLAYER), eq(PUNISHMENT), sent.capture(), any());
        assertThat(sent.getValue().issuerName()).isEqualTo("RealMod");
        assertThat(sent.getValue().issuerId()).isEqualTo("staff-real");
    }

    @Test
    void punishmentNotesTakeTheIssuerFromTheSession() {
        controller.addPunishmentNote(PLAYER.toString(), PUNISHMENT, PanelAddPunishmentNoteRequest.newBuilder()
            .setText("note").build(), sessionOf(STAFF_EMAIL));

        verify(evidence).addPunishmentNote(server, PLAYER, PUNISHMENT, "note", "RealMod", "staff-real");
    }

    @Test
    void evidenceTakesTheIssuerFromTheSession() {
        controller.addEvidence(PLAYER.toString(), PUNISHMENT, PanelAddEvidenceRequest.newBuilder()
            .setType("text").setText("proof").build(), sessionOf(STAFF_EMAIL));

        ArgumentCaptor<AddEvidenceRequest> sent = ArgumentCaptor.forClass(AddEvidenceRequest.class);
        verify(evidence).addEvidence(eq(server), eq(PLAYER), eq(PUNISHMENT), sent.capture());
        assertThat(sent.getValue().issuerName()).isEqualTo("RealMod");
        assertThat(sent.getValue().issuerId()).isEqualTo("staff-real");
    }

    @Test
    void ticketChangesTakeTheIssuerFromTheSession() {
        controller.modifyPunishmentTickets(PLAYER.toString(), PUNISHMENT, ModifyPunishmentTicketsRequest.newBuilder()
            .addAddTicketIds("T-1").setIssuerName("Owner").setIssuerId("staff-victim").build(), sessionOf(STAFF_EMAIL));

        verify(mutations).modifyPunishmentTickets(server, PLAYER, PUNISHMENT,
            PanelPlayerProtoMapper.fromModifyTickets(
                ModifyPunishmentTicketsRequest.newBuilder().addAddTicketIds("T-1").build(), SESSION_STAFF));
    }

    @Test
    void playerNotesTakeTheIssuerFromTheSession() {
        controller.addNote(PLAYER.toString(), CreatePlayerNoteRequest.newBuilder()
            .setText("note").setIssuerName("Owner").setIssuerId("staff-victim").build(), sessionOf(STAFF_EMAIL));

        verify(players).addNote(server, PLAYER, "note", "RealMod", "staff-real");
    }

    @Test
    void theOwnerWithoutAStaffRecordIssuesAsAdmin() {
        controller.addPunishmentNote(PLAYER.toString(), PUNISHMENT, PanelAddPunishmentNoteRequest.newBuilder()
            .setText("note").build(), sessionOf(OWNER_EMAIL));

        verify(evidence).addPunishmentNote(server, PLAYER, PUNISHMENT, "note", "Admin", null);
    }

    @Test
    void aSessionThatIsNeitherStaffNorOwnerIssuesNothing() {
        HttpServletRequest request = sessionOf("former@example.com");

        assertThatThrownBy(() -> controller.addPunishmentNote(PLAYER.toString(), PUNISHMENT,
            PanelAddPunishmentNoteRequest.newBuilder().setText("note").build(), request))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(evidence);
    }

    private HttpServletRequest sessionOf(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AuthSessionData session = new AuthSessionData();
        session.setEmail(email);
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, session);
        return request;
    }
}
