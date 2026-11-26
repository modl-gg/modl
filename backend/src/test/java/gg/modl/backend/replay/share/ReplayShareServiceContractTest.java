package gg.modl.backend.replay.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.log.service.AuditActors;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayShare;
import gg.modl.backend.replay.repository.JooqReplayRepository;
import gg.modl.backend.replay.repository.JooqReplayShareRepository;
import gg.modl.backend.replay.service.ReplayFile;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class ReplayShareServiceContractTest {
    private static final String ACTOR = "mod@example.com";
    private static final String VIEWER = "198.51.100.7";
    private final Server server = TestServers.server("server", "owner@example.com");
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final ReplayService replayService = mock(ReplayService.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aCreatedLinkOpensUntilItIsRevokedAndStoresOnlyTheTokenHash(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReplayShareService service = service(database);
            insertReplay(database, "replay", ReplayDocument.STATUS_COMPLETE);

            CreatedReplayShare created = service.create(server, ACTOR, "replay", ReplayShareExpiry.SEVEN_DAYS);

            assertThat(created.url()).isEqualTo("https://panel.example/replay/" + created.token());
            assertThat(created.share().tokenHash()).isEqualTo(ReplayShareTokens.hash(created.token())).isNotEqualTo(created.token());
            assertThat(created.share().createdBy()).isEqualTo("Moderator");
            assertThat(created.share().expiresAt()).isEqualTo(SqlTestDatabase.START.plus(Duration.ofDays(7)));
            assertThat(service.open(server, created.token(), VIEWER)).get()
                .satisfies(shared -> {
                    assertThat(shared.serverName()).isEqualTo("Example Network");
                    assertThat(shared.fileUrl()).isEqualTo("/v1/public/replays/shared/" + created.token() + "/file");
                });
            verify(auditor).recordStaffAction(eq(server), eq(ACTOR), eq("Shared the replay of Target, expires 2026-01-08T00:00:00Z"));

            service.revoke(server, ACTOR, "replay", created.share().id());
            service.revoke(server, ACTOR, "replay", created.share().id());

            assertThat(service.open(server, created.token(), VIEWER)).isEmpty();
            verify(auditor, times(1)).recordStaffAction(eq(server), eq(ACTOR), eq("Revoked a share link of the replay of Target"));
            assertThat(service.list("replay")).singleElement().extracting(ReplayShare::revokedAt).isNotNull();
        }
    }

    @SqlContractTest
    void anExpiredLinkAndAMalformedTokenOpenNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReplayShareService service = service(database);
            insertReplay(database, "replay", ReplayDocument.STATUS_COMPLETE);
            CreatedReplayShare created = service.create(server, ACTOR, "replay", ReplayShareExpiry.ONE_DAY);

            database.clock().advance(Duration.ofDays(1));

            assertThat(service.open(server, created.token(), VIEWER)).isEmpty();
            assertThat(service.open(server, "short", VIEWER)).isEmpty();
            assertThat(service.openFile(created.token())).isEmpty();
        }
    }

    @SqlContractTest
    void aLinkThatNeverExpiresKeepsOpening(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReplayShareService service = service(database);
            insertReplay(database, "replay", ReplayDocument.STATUS_COMPLETE);
            CreatedReplayShare created = service.create(server, ACTOR, "replay", ReplayShareExpiry.NEVER);

            database.clock().advance(Duration.ofDays(3650));

            assertThat(created.share().expiresAt()).isNull();
            assertThat(service.open(server, created.token(), VIEWER)).isPresent();
            verify(auditor).recordStaffAction(eq(server), eq(ACTOR), eq("Shared the replay of Target, never expires"));
        }
    }

    @SqlContractTest
    void eachViewerCountsOncePerWindowAndTheFileIsAPureRead(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReplayShareViews views = views(database);
            ReplayShareService service = service(database, views);
            insertReplay(database, "replay", ReplayDocument.STATUS_COMPLETE);
            when(replayService.completedFile("replay")).thenReturn(Optional.of(mock(ReplayFile.class)));
            CreatedReplayShare created = service.create(server, ACTOR, "replay", ReplayShareExpiry.SEVEN_DAYS);

            service.open(server, created.token(), VIEWER);
            service.open(server, created.token(), VIEWER);
            service.open(server, created.token(), "203.0.113.9");
            assertThat(service.openFile(created.token())).isPresent();
            assertThat(service.list("replay")).singleElement().extracting(ReplayShare::viewCount).isEqualTo(0L);

            views.flush();
            assertThat(service.list("replay")).singleElement().satisfies(share -> {
                assertThat(share.viewCount()).isEqualTo(2L);
                assertThat(share.lastViewedAt()).isEqualTo(database.clock().instant());
            });

            database.clock().advance(ReplayShareViews.VIEWER_WINDOW);
            service.open(server, created.token(), VIEWER);
            views.flush();
            assertThat(service.list("replay")).singleElement().extracting(ReplayShare::viewCount).isEqualTo(3L);
        }
    }

    @SqlContractTest
    void incompleteOrUnknownReplaysCannotBeSharedAndTheLinkCountIsCapped(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            ReplayShareService service = service(database);
            insertReplay(database, "pending", ReplayDocument.STATUS_PENDING);
            insertReplay(database, "replay", ReplayDocument.STATUS_COMPLETE);

            assertThatThrownBy(() -> service.create(server, ACTOR, "pending", ReplayShareExpiry.ONE_DAY))
                .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.list("missing")).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.revoke(server, ACTOR, "replay", "missing"))
                .isInstanceOf(ResourceNotFoundException.class);

            IntStream.range(0, ReplayShareService.MAX_OPEN_SHARES_PER_REPLAY)
                .forEach(index -> service.create(server, ACTOR, "replay", ReplayShareExpiry.ONE_DAY));
            assertThatThrownBy(() -> service.create(server, ACTOR, "replay", ReplayShareExpiry.ONE_DAY))
                .isInstanceOf(ConflictException.class);
            service.revoke(server, ACTOR, "replay", service.list("replay").getFirst().id());
            service.create(server, ACTOR, "replay", ReplayShareExpiry.ONE_DAY);
        }
    }

    private ReplayShareService service(SqlTestDatabase database) {
        return service(database, views(database));
    }

    private static ReplayShareViews views(SqlTestDatabase database) {
        ScheduledDatabaseJobs jobs = mock(ScheduledDatabaseJobs.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(jobs).run(anyString(), any());
        return new ReplayShareViews(new JooqReplayShareRepository(database.dsl(), database.childRows()), database.unitOfWork(), jobs,
            database.clock());
    }

    private ReplayShareService service(SqlTestDatabase database, ReplayShareViews views) {
        GeneralSettingsService general = mock(GeneralSettingsService.class);
        when(general.getGeneralSettings(any())).thenReturn(GeneralSettings.builder().serverDisplayName("Example Network").build());
        AuditActors actors = mock(AuditActors.class);
        when(actors.account(any(), any())).thenReturn(AuditActor.named("Moderator"));
        PublicUrls urls = mock(PublicUrls.class);
        when(urls.sharedReplay(anyString())).thenAnswer(call -> "https://panel.example/replay/" + call.getArgument(0));
        return new ReplayShareService(new JooqReplayRepository(database.dsl(), database.jsonColumns()),
            new JooqReplayShareRepository(database.dsl(), database.childRows()), new ReplayShareTokens(), replayService, general, actors, auditor, urls,
            views, database.unitOfWork(), database.clock());
    }

    private static void insertReplay(SqlTestDatabase database, String id, String status) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId(id);
        replay.setStatus(status);
        replay.setTargetName("Target");
        replay.setCreatedAt(SqlTestDatabase.START);
        replay.setStorageKey("modl_instance/replays/" + id + ".modlreplay");
        JooqReplayRepository replays = new JooqReplayRepository(database.dsl(), database.jsonColumns());
        database.unitOfWork().write(() -> replays.insert(replay));
    }
}
