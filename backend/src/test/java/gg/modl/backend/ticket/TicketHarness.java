package gg.modl.backend.ticket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.service.PlayerStatusCalculator;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.StaffNotices;
import gg.modl.backend.player.service.SyncProtoFactory;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.settings.service.QuickResponseSettingsService;
import gg.modl.backend.settings.service.TicketFormSettingsService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.service.StoredObjectUrlResolver;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.JooqTicketRepository;
import gg.modl.backend.ticket.repository.JooqTicketVerificationRepository;
import gg.modl.backend.ticket.service.AppealWorkflowTransitionService;
import gg.modl.backend.ticket.service.ContactEmailPolicy;
import gg.modl.backend.ticket.service.MinecraftTicketService;
import gg.modl.backend.ticket.service.PublicRecordAccessService;
import gg.modl.backend.ticket.service.TicketAccessKeys;
import gg.modl.backend.ticket.service.TicketChangeAuthorizer;
import gg.modl.backend.ticket.service.TicketContentService;
import gg.modl.backend.ticket.service.TicketEmailVerificationService;
import gg.modl.backend.ticket.service.TicketInserter;
import gg.modl.backend.ticket.service.TicketMessages;
import gg.modl.backend.ticket.service.TicketNotificationService;
import gg.modl.backend.ticket.service.TicketOpenedAnnouncer;
import gg.modl.backend.ticket.service.TicketPublicLinks;
import gg.modl.backend.ticket.service.TicketReplyAppender;
import gg.modl.backend.ticket.service.TicketReplyService;
import gg.modl.backend.ticket.service.TicketResponseAssembler;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.ticket.service.TicketSubscriptionService;
import gg.modl.backend.ticket.service.TicketUploads;
import gg.modl.backend.webhook.WebhookPublisher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public record TicketHarness(
    SqlTestDatabase database,
    JooqTicketRepository tickets,
    JooqTicketVerificationRepository verifications,
    StaffRepository staff,
    StorageFileRepository storageFiles,
    TicketFormSettingsService forms,
    QuickResponseSettingsService quickResponses,
    WebhookPublisher webhooks,
    RealtimeEventPublisher realtime,
    TicketNotificationService notifications,
    TicketEmailVerificationService verificationService
) implements AutoCloseable {
    public static final Instant NOW = SqlTestDatabase.START;
    public static final String ACCESS_KEY = "k3y-0f-th3-t1ck3t-k3y-0f-th3-t1ck3t-k3y-0f";
    public static final String OBJECTS_PREFIX = "/v1/storage/objects/";
    public static final String UPLOAD_KEY = "instance/tickets/new/shot.png";
    public static final String UPLOAD_URL = OBJECTS_PREFIX + UPLOAD_KEY;
    public static final String STAFF_TICKETS = "https://panel.example.com/panel/tickets/";

    private static final EmailCapability DELIVERY = () -> true;

    public static TicketHarness start(DialectTarget target, Path dataDirectory) {
        return start(target, dataDirectory, WriteAttemptHook.NONE);
    }

    public static TicketHarness start(DialectTarget target, Path dataDirectory, WriteAttemptHook hook) {
        SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, hook);
        StorageFileRepository storageFiles = mock(StorageFileRepository.class);
        when(storageFiles.markAttached(any())).thenAnswer(invocation -> invocation.<Collection<String>>getArgument(0).size());
        return new TicketHarness(
            database,
            new JooqTicketRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(), database.childRows(),
                database.jsonColumns()),
            new JooqTicketVerificationRepository(database.dsl(), database.childRows(), database.unitOfWork()),
            mock(StaffRepository.class),
            storageFiles,
            mock(TicketFormSettingsService.class),
            mock(QuickResponseSettingsService.class),
            mock(WebhookPublisher.class),
            mock(RealtimeEventPublisher.class),
            mock(TicketNotificationService.class),
            mock(TicketEmailVerificationService.class));
    }

    public static StoredObjectUrlResolver localUrls() {
        StoredObjectUrlResolver resolver = mock(StoredObjectUrlResolver.class);
        when(resolver.keyOf(any())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            return url != null && url.startsWith(OBJECTS_PREFIX)
                ? Optional.of(url.substring(OBJECTS_PREFIX.length()))
                : Optional.empty();
        });
        return resolver;
    }

    public TicketContentService content() {
        return new TicketContentService(localUrls());
    }

    public TicketMessages messages() {
        return new TicketMessages(content());
    }

    public TicketUploads uploads() {
        return new TicketUploads(localUrls(), storageFiles);
    }

    public TicketInserter inserter() {
        IdGenerator ids = new IdGenerator();
        return new TicketInserter(ids, new TicketAccessKeys(ids), tickets, database.unitOfWork());
    }

    public TicketOpenedAnnouncer announcer() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.staffTicket(any())).thenAnswer(invocation -> STAFF_TICKETS + invocation.getArgument(0));
        return new TicketOpenedAnnouncer(database.unitOfWork(), webhooks, realtime, new SyncProtoFactory(),
            new StaffNotices(database.clock(), urls, mock(PlayerStatusCalculator.class)));
    }

    public TicketPublicLinks links() {
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.ticket(any(), any())).thenAnswer(invocation ->
            "https://panel.example.com/ticket/" + invocation.getArgument(0) + "?key=" + invocation.getArgument(1));
        return new TicketPublicLinks(urls);
    }

    public TicketResponseAssembler assembler() {
        return new TicketResponseAssembler(staff, links());
    }

    public TicketService ticketService() {
        return ticketService(mock(TicketChangeAuthorizer.class));
    }

    public TicketService ticketService(TicketChangeAuthorizer changeAuthorizer) {
        return ticketService(changeAuthorizer, mock(PunishmentModificationAuthorizer.class));
    }

    public TicketService ticketService(TicketChangeAuthorizer changeAuthorizer,
                                       PunishmentModificationAuthorizer modificationAuthorizer) {
        return new TicketService(tickets, assembler(), quickResponses, forms, notifications, inserter(), content(), messages(),
            uploads(), announcer(), new ContactEmailPolicy(DELIVERY), changeAuthorizer, modificationAuthorizer, transitions(),
            database.unitOfWork(),
            database.clock());
    }

    public TicketReplyAppender appender() {
        return new TicketReplyAppender(tickets, uploads());
    }

    public TicketReplyService replyService() {
        return replyService(mock(TicketChangeAuthorizer.class));
    }

    public TicketReplyService replyService(TicketChangeAuthorizer changeAuthorizer) {
        return new TicketReplyService(tickets, notifications, messages(), appender(), uploads(), changeAuthorizer,
            database.unitOfWork(), database.clock());
    }

    public MinecraftTicketService minecraftService() {
        return minecraftService(mock(MinecraftIssuerAuthorizer.class));
    }

    public MinecraftTicketService minecraftService(MinecraftIssuerAuthorizer issuerAuthorizer) {
        PublicRecordAccessService access = new PublicRecordAccessService(
            new TicketAccessKeys(new IdGenerator()), verificationService, DELIVERY);
        return new MinecraftTicketService(tickets, notifications, inserter(), announcer(), links(), access, issuerAuthorizer,
            database.unitOfWork(), database.clock());
    }

    public AppealWorkflowTransitionService transitions() {
        return new AppealWorkflowTransitionService(tickets, notifications, database.unitOfWork(), database.clock());
    }

    public TicketSubscriptionService subscriptions() {
        return new TicketSubscriptionService(staff, tickets, database.unitOfWork(), database.clock());
    }

    public static Ticket.TicketBuilder ticket(String id) {
        return Ticket.builder()
            .id(id)
            .type(TicketCategory.SUPPORT)
            .status(TicketStatus.OPEN)
            .subject("Need help")
            .publicAccessKey(ACCESS_KEY)
            .created(NOW)
            .updatedAt(NOW);
    }

    public static TicketReply.TicketReplyBuilder reply(String id) {
        return TicketReply.builder().id(id).name("Player").content("content of " + id).type("user").created(NOW);
    }

    public void insert(Ticket.TicketBuilder... builders) {
        database.unitOfWork().write(() -> {
            for (Ticket.TicketBuilder builder : builders) {
                if (!tickets.insertIfAbsent(builder.build())) {
                    throw new IllegalStateException("The fixture ticket already exists");
                }
            }
        });
    }

    public Ticket stored(String id) {
        return tickets.findById(id).orElseThrow();
    }

    @Override
    public void close() {
        database.close();
    }
}
