package gg.modl.backend.ticket.service;

import static gg.modl.backend.ticket.TicketHarness.NOW;
import static gg.modl.backend.ticket.TicketHarness.reply;
import static gg.modl.backend.ticket.TicketHarness.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.appeal.dto.request.CreateAppealRequest;
import gg.modl.backend.appeal.dto.request.UpdateAppealStatusRequest;
import gg.modl.backend.appeal.service.AppealService;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.ticket.TicketHarness;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppealLifecycleContractTest {
    private static final String PUNISHMENT = "BANAAAAA";
    private static final String PLAYER_UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String APPEAL = "APPEAL-100001";

    private final Server server = TestServers.server("Demo", "admin@example.com");
    private final PunishmentRepository punishments = mock(PunishmentRepository.class);
    private final PunishmentMutationService mutations = mock(PunishmentMutationService.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void closingAnUndecidedAppealLetsThePlayerAppealAgain(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            appealablePunishment(true);
            harness.insert(appeal(AppealWorkflowStatus.OPEN));

            harness.ticketService().updateTicket(server, APPEAL, update("closed", true), "mod@example.com");

            assertThat(harness.stored(APPEAL).getAppealWorkflowStatus()).isEqualTo(AppealWorkflowStatus.CLOSED);
            appeals(harness).createAppeal(server, appealRequest());
            assertThat(harness.tickets().findAppealsByPunishmentId(PUNISHMENT)).hasSize(2);
        }
    }

    @SqlContractTest
    void aClosedAppealCannotBeReopenedWhileANewerOneIsInProgress(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            appealablePunishment(true);
            harness.insert(appeal(AppealWorkflowStatus.OPEN));
            harness.ticketService().updateTicket(server, APPEAL, update("closed", true), "mod@example.com");
            appeals(harness).createAppeal(server, appealRequest());

            assertThatThrownBy(() -> harness.ticketService().updateTicket(server, APPEAL, update("open", false), "mod@example.com"))
                .isInstanceOfSatisfying(ConflictException.class, refusal -> assertThat(refusal.getMachineCode())
                    .isEqualTo(AppealWorkflowTransitionService.APPEAL_IN_PROGRESS));
            assertThatThrownBy(() -> appeals(harness).updateStatus(server, APPEAL,
                new UpdateAppealStatusRequest("open", null, "Admin", null), "mod@example.com")).isInstanceOf(ConflictException.class);

            assertThat(harness.stored(APPEAL).getAppealWorkflowStatus()).isEqualTo(AppealWorkflowStatus.CLOSED);
            assertThat(harness.tickets().findAppealSummariesByPunishmentId(PUNISHMENT))
                .filteredOn(summary -> !summary.getAppealWorkflowStatus().isTerminal()).hasSize(1);
        }
    }

    @SqlContractTest
    void aRejectedAppealReopenedInThePanelCanStillBeApproved(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            appealablePunishment(true);
            harness.insert(appeal(AppealWorkflowStatus.REJECTED).status(TicketStatus.CLOSED).locked(true));

            harness.ticketService().updateTicket(server, APPEAL, update("open", false), "mod@example.com");
            assertThat(harness.stored(APPEAL).getAppealWorkflowStatus()).isEqualTo(AppealWorkflowStatus.OPEN);

            appeals(harness).updateStatus(server, APPEAL, new UpdateAppealStatusRequest("approved", null, "Admin", null),
                "mod@example.com");

            assertThat(harness.stored(APPEAL).getAppealWorkflowStatus()).isEqualTo(AppealWorkflowStatus.APPROVED);
            verify(mutations, atLeastOnce())
                .applyAppealApproval(eq(server), eq(PLAYER_UUID), eq(PUNISHMENT), any(), any(), eq(APPEAL));
        }
    }

    @SqlContractTest
    void aNonAppealablePunishmentCannotBeAppealed(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            appealablePunishment(false);

            assertThatThrownBy(() -> appeals(harness).createAppeal(server, appealRequest()))
                .isInstanceOfSatisfying(ValidationException.class,
                    rejection -> assertThat(rejection.getMachineCode()).isEqualTo("PUNISHMENT_NOT_APPEALABLE"));
            assertThat(harness.tickets().findAppealsByPunishmentId(PUNISHMENT)).isEmpty();
        }
    }

    @SqlContractTest
    void aSubmittedTicketCannotBeRewrittenByThePlayer(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            harness.insert(ticket("SUPPORT-100001").status(TicketStatus.OPEN).subject("Original"));

            assertThatThrownBy(() -> harness.ticketService().submitTicketForm(server, "SUPPORT-100001",
                new SubmitTicketFormRequest("Appeal for Punishment: OTHER", null, Map.of("q", "a"), null, null, null, null), false))
                .isInstanceOf(ConflictException.class);
            assertThat(harness.stored("SUPPORT-100001").getSubject()).isEqualTo("Original");
        }
    }

    @SqlContractTest
    void aFullConversationRefusesFurtherPlayerReplies(DialectTarget target) {
        try (TicketHarness harness = start(target)) {
            List<TicketReply> replies = new ArrayList<>(IntStream.range(0, TicketInputLimits.MAX_THREAD_REPLIES_FOR_PLAYERS)
                .mapToObj(index -> reply("r" + index).build()).toList());
            harness.insert(ticket("SUPPORT-100001").replies(replies), ticket("SUPPORT-100002"));

            assertThatThrownBy(() -> harness.replyService().addPublicReply(server, "SUPPORT-100001", "one more", null))
                .isInstanceOfSatisfying(ConflictException.class,
                    rejection -> assertThat(rejection.getMachineCode()).isEqualTo(TicketInputLimits.THREAD_FULL));
            assertThatThrownBy(() -> harness.replyService().addPublicReply(server, "SUPPORT-100002",
                "x".repeat(TicketInputLimits.MAX_PLAYER_REPLY_CHARACTERS + 1), null)).isInstanceOf(ValidationException.class);
            harness.replyService().addPublicReply(server, "SUPPORT-100002", "fits", null);
            assertThat(harness.stored("SUPPORT-100001").getReplies()).hasSize(TicketInputLimits.MAX_THREAD_REPLIES_FOR_PLAYERS);
        }
    }

    @Test
    void anAppealIsNeverOpenedAsAPlainTicket() {
        assertThatThrownBy(() -> TicketCategories.requireOpenableAsTicket(TicketCategory.APPEAL))
            .isInstanceOf(ValidationException.class);
    }

    private void appealablePunishment(boolean appealable) {
        Punishment punishment = new Punishment();
        punishment.setId(PUNISHMENT);
        punishment.setIssued(NOW);
        punishment.setStarted(NOW);
        punishment.setModifications(new ArrayList<>());
        punishment.replaceData(new HashMap<>(Map.of("altBlocking", false)));
        when(punishments.findById(PUNISHMENT)).thenReturn(Optional.of(new PunishmentRecord("player-1", PLAYER_UUID, "Player", punishment, 0)));
        when(types.isAppealable(any(), anyInt())).thenReturn(appealable);
    }

    private static Ticket.TicketBuilder appeal(AppealWorkflowStatus workflow) {
        return ticket(APPEAL)
            .type(TicketCategory.APPEAL)
            .appealWorkflowStatus(workflow)
            .data(Map.of("punishmentId", PUNISHMENT, "playerUuid", PLAYER_UUID));
    }

    private static UpdateTicketRequest update(String status, boolean locked) {
        return new UpdateTicketRequest(status, locked, null, null, null, null, null, null, "Mod");
    }

    private static CreateAppealRequest appealRequest() {
        return new CreateAppealRequest(PUNISHMENT, PLAYER_UUID, null, "Please unban me", null, Map.of(), List.of(), null);
    }

    private AppealService appeals(TicketHarness harness) {
        return new AppealService(harness.tickets(), punishments, mutations, mock(PunishmentLifecycleService.class),
            mock(PunishmentModificationAuthorizer.class), harness.inserter(), harness.announcer(), harness.transitions(), harness.content(), harness.messages(), harness.appender(), harness.uploads(),
            harness.assembler(), new ContactEmailPolicy(() -> false), types, harness.database().unitOfWork(),
            harness.database().clock());
    }

    private TicketHarness start(DialectTarget target) {
        return TicketHarness.start(target, dataDirectory, new RetryInjection());
    }
}
