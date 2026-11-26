package gg.modl.backend.hostedimport.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.archive.HostedArchive;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.mapping.ImportContext;
import gg.modl.backend.hostedimport.mapping.RoleMapper;
import gg.modl.backend.hostedimport.mapping.SettingsImport.SettingsImportPlan;
import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.model.ImportChoices;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportSource;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.model.RetentionChoice;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import gg.modl.backend.hostedimport.model.SubjectCount;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class ConfigurationStepTest {
    @TempDir
    Path directory;

    @Test
    void theDefaultRolesAreRefilledInTheSameUnitThatReplacesThem() throws Exception {
        HostedArchiveFixture fixture = new HostedArchiveFixture()
            .line("staffroles", "{\"_id\":\"mods\",\"name\":\"Mods\",\"permissions\":[]}");
        ArchiveIndex index = ImportTestContexts.index(fixture, directory.resolve("a"));
        ConfigurationWriter writer = mock(ConfigurationWriter.class);
        RoleService roleService = mock(RoleService.class);
        RecordUnits units = mock(RecordUnits.class);
        AtomicReference<ImportTally> committed = new AtomicReference<>();
        doAnswer(call -> {
            committed.set(((IssueLog) call.getArgument(2)).tally());
            ((Runnable) call.getArgument(3)).run();
            return null;
        }).when(units).commitState(anyString(), anyLong(), any(IssueLog.class), any(Runnable.class));
        doAnswer(call -> null).when(units).commit(anyString(), any(), any(), any(), any(IssueLog.class));
        ImportArchiveFiles files = mock(ImportArchiveFiles.class);
        when(files.shortfall(anyLong())).thenReturn(0L);
        Server server = TestServers.server("Server", "owner@example.com");
        ImportContext context = ImportTestContexts.context(index);
        RoleMapper roles = new RoleMapper(context, List.of(), Set.of());
        ImportPlan plan = new ImportPlan(context, new SettingsImportPlan(Optional.empty(), context.types(), Map.of(), null,
            Set.of("general", "ticketForms"), Set.of("quickResponses")), roles, StaffPlan.of(index, context.ids(), "owner@example.com", roles));
        ImportJob job = ImportJob.started("aaaaaaaa-0a53-4b8e-9d1c-0c8f0f0d2a11", ImportSource.UPLOAD, null, 1, null, ImportTestContexts.NOW)
            .withStatus(ImportStatus.APPLYING).withPhase(ImportPhase.CONFIGURATION);
        Path archivePath = fixture.write(directory);

        try (HostedArchive archive = HostedArchive.open(archivePath)) {
            new ConfigurationStep(writer, roleService, Clock.fixed(ImportTestContexts.NOW, ZoneOffset.UTC)).run(PhaseRun.builder()
                .job(job).server(server).ownerEmail("owner@example.com").archive(archive).plan(plan)
                .choices(new ImportChoices(Set.of(), false, new RetentionPlan(RetentionChoice.STANDARD, 30, 365, true, 10, false, 0, 0, 0, 0)))
                .units(units).files(files).stopRequested(() -> false).build());
        }

        InOrder order = inOrder(writer, roleService);
        order.verify(writer).write(any(), any(), any(), any(), any());
        order.verify(roleService).createDefaultRoles(server);
        assertThat(committed.get().count(ImportSubject.ROLES)).isEqualTo(new SubjectCount(0, 1, 0, 0));
        assertThat(committed.get().count(ImportSubject.SETTINGS)).isEqualTo(new SubjectCount(0, 2, 0, 1));
    }

    @Test
    void aResumedConfigurationDoesNotWriteTheConfigurationAgain() throws Exception {
        HostedArchiveFixture fixture = new HostedArchiveFixture();
        ArchiveIndex index = ImportTestContexts.index(fixture, directory.resolve("b"));
        ConfigurationWriter writer = mock(ConfigurationWriter.class);
        RoleService roleService = mock(RoleService.class);
        RecordUnits units = mock(RecordUnits.class);
        ImportArchiveFiles files = mock(ImportArchiveFiles.class);
        ImportContext context = ImportTestContexts.context(index);
        RoleMapper roles = new RoleMapper(context, List.of(), Set.of());
        ImportPlan plan = new ImportPlan(context, new SettingsImportPlan(Optional.empty(), context.types(), Map.of(), null, Set.of(), Set.of()), roles,
            StaffPlan.of(index, context.ids(), "owner@example.com", roles));
        ImportJob job = ImportJob.started("aaaaaaaa-0a53-4b8e-9d1c-0c8f0f0d2a11", ImportSource.UPLOAD, null, 1, null, ImportTestContexts.NOW)
            .withStatus(ImportStatus.APPLYING).withPhase(ImportPhase.CONFIGURATION).withCursor(1);
        Path archivePath = fixture.write(directory);

        try (HostedArchive archive = HostedArchive.open(archivePath)) {
            new ConfigurationStep(writer, roleService, Clock.fixed(ImportTestContexts.NOW, ZoneOffset.UTC)).run(PhaseRun.builder()
                .job(job).server(TestServers.server("Server", "owner@example.com")).ownerEmail("owner@example.com").archive(archive).plan(plan)
                .choices(new ImportChoices(Set.of(), false, new RetentionPlan(RetentionChoice.STANDARD, 30, 365, true, 10, false, 0, 0, 0, 0)))
                .units(units).files(files).stopRequested(() -> false).build());
        }

        verify(writer, never()).write(any(), any(), any(), any(), any());
        verify(roleService, never()).createDefaultRoles(any());
    }
}
