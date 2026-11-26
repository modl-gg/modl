package gg.modl.backend.hostedimport.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueEntry;
import gg.modl.backend.role.data.Permission;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.staff.data.Staff;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaffAndRoleMappingTest {
    private static final List<Permission> BASE = List.of(
        new Permission("ticket.view.all", "View", "", "ticket", null, false),
        new Permission("ticket.reply.all", "Reply", "", "ticket", null, false),
        new Permission("admin.settings.modify", "Modify", "", "admin", null, true));

    @TempDir
    Path directory;

    @Test
    void applyPermissionsMapBySlugToOrdinalsAndPrivilegedOnesAreDropped() throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .line("staffroles", "{\"_id\":\"mods\",\"name\":\"Super Admin\",\"order\":-5,\"permissions\":"
                + "[\"punishment.apply.chat-abuse\",\"punishment.apply.cheating\",\"ticket\",\"admin.settings.modify\",\"punishment.apply.gone\"]}")
            .line("staffroles", "{\"_id\":\"super-admin\",\"name\":\"Owner role\",\"permissions\":[\"*\"]}"), directory);
        List<PunishmentType> types = List.of(type(6, "Chat Abuse"), type(7, "Cheating"), type(8, "Cheating.Fly"));
        ImportContext context = ImportTestContexts.context(index, types, Map.of(), false);
        RoleMapper roles = new RoleMapper(context, BASE, Set.of());

        assertThat(roles.roles()).hasSize(1);
        StaffRole role = roles.roles().getFirst();
        assertThat(role.getName()).isEqualTo("Super Admin (imported)");
        assertThat(context.issues().tally().issues().get(IssueCode.ROLE_RENAMED)).isEqualTo(new IssueEntry(1, List.of("Super Admin (imported)")));
        assertThat(context.issues().tally().counts()).doesNotContainKey(ImportSubject.ROLES);
        assertThat(role.getOrder()).isEqualTo(1);
        assertThat(role.getPermissions()).containsExactlyInAnyOrder("punishment.apply.6", "punishment.apply.7", "punishment.apply.8",
            "ticket.view.all", "ticket.reply.all", "player.note", "player.warn");
    }

    @Test
    void ticketNotesHostedGaveWithTheParentStayGrantedAndRemovedPermissionsAreDropped() throws IOException {
        List<Permission> catalog = List.of(
            new Permission("ticket.view.all", "View", "", "ticket", null, false),
            new Permission("ticket.view.all.notes", "View notes", "", "ticket", null, false),
            new Permission("ticket.reply.all", "Reply", "", "ticket", null, false),
            new Permission("ticket.reply.all.notes", "Add notes", "", "ticket", null, false));
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .line("staffroles", "{\"_id\":\"support\",\"name\":\"Support\",\"permissions\":"
                + "[\"ticket.view.all\",\"ticket.reply.all\",\"staff.maintenance\",\"ticket.delete.all\",\"ticket.manage.subscribe\"]}")
            .line("staffroles", "{\"_id\":\"reader\",\"name\":\"Reader\",\"permissions\":[\"ticket.view.all.notes\"]}"), directory);

        ImportContext context = ImportTestContexts.context(index);
        RoleMapper roles = new RoleMapper(context, catalog, Set.of());

        assertThat(roles.roleFor("support").orElseThrow().getPermissions()).containsExactlyInAnyOrder(
            "ticket.view.all", "ticket.view.all.notes", "ticket.reply.all", "ticket.reply.all.notes", "player.note", "player.warn");
        IssueEntry dropped = context.issues().tally().issues().get(IssueCode.PERMISSION_DROPPED);
        assertThat(dropped.count()).isEqualTo(3);
        assertThat(dropped.samples()).containsExactlyInAnyOrder("Maintenance Mode", "Delete Tickets", "Manage Subscriptions");
        assertThat(roles.roleFor("reader").orElseThrow().getPermissions())
            .containsExactlyInAnyOrder("ticket.view.all.notes", "player.note", "player.warn");
    }

    @Test
    void everyImportedRoleAddsNotesAndWarnsAndOnlyTheHelperIsGivenViewing() throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .line("staffroles", "{\"_id\":\"helper\",\"name\":\"Trainee\",\"isDefault\":true,"
                + "\"permissions\":[\"ticket.view.all\"]}")
            .line("staffroles", "{\"_id\":\"older-id\",\"name\":\"Helper\",\"isDefault\":true,\"permissions\":[]}")
            .line("staffroles", "{\"_id\":\"custom-1\",\"name\":\"helper\",\"isDefault\":false,\"permissions\":[]}")
            .line("staffroles", "{\"_id\":\"custom-2\",\"name\":\"Support\",\"permissions\":[]}"), directory);

        RoleMapper roles = new RoleMapper(ImportTestContexts.context(index), BASE, Set.of());

        assertThat(roles.roleFor("helper").orElseThrow().getPermissions())
            .containsExactlyInAnyOrder("ticket.view.all", "player.note", "player.warn", "punishment.view");
        assertThat(roles.roleFor("older-id").orElseThrow().getPermissions())
            .containsExactlyInAnyOrder("player.note", "player.warn", "punishment.view");
        for (String custom : List.of("custom-1", "custom-2")) {
            assertThat(roles.roleFor(custom).orElseThrow().getPermissions())
                .containsExactlyInAnyOrder("player.note", "player.warn");
        }
    }

    @Test
    void staffAreNeverPreselectedTheOwnerIsBlockedAndCredentialFieldsAreNeverCopied() throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture()
            .adminEmail("old-owner@example.com")
            .line("staffs", "{\"_id\":\"s1\",\"email\":\"Mod@Example.com\",\"username\":\"mod\",\"role\":\"super-admin\","
                + "\"twoFactorToken\":\"secret\",\"password\":\"hash\",\"totpSecret\":\"seed\",\"language\":\"de\"}")
            .line("staffs", "{\"_id\":\"s2\",\"email\":\"owner@example.com\",\"username\":\"owner\"}")
            .line("staffs", "{\"_id\":\"s3\",\"email\":\"not an email\",\"username\":\"broken\"}")
            .line("staffs", "{\"_id\":\"s4\",\"email\":\"old-owner@example.com\",\"username\":\"old\"}"), directory);
        ImportContext context = ImportTestContexts.context(index);
        StaffPlan plan = StaffPlan.of(index, context.ids(), "owner@example.com", new RoleMapper(context, BASE, Set.of()));

        assertThat(plan.candidates()).extracting(StaffPlan.Candidate::blockedCode)
            .containsExactly(null, StaffPlan.BLOCKED_OWNER, StaffPlan.BLOCKED_INVALID_EMAIL, null);
        assertThat(plan.candidates().getFirst().roleId()).isNull();
        assertThat(plan.archivedOwner()).map(StaffPlan.Candidate::sourceId).contains("s4");
        assertThat(plan.ownerDocument(false)).map(StaffPlan.Candidate::sourceId).contains("s2");
        assertThat(plan.selectionProblem(Set.of("s2"), false)).contains("s2");
        assertThat(plan.selectionProblem(Set.of("s1"), false)).isEmpty();
        assertThat(plan.restoredStaffIds(Set.of(), false, "owner-id")).containsOnlyKeys("s2");

        Staff restored = StaffRecords.toStaff(plan.candidates().getFirst(), context.fit(), ImportTestContexts.NOW);
        assertThat(restored.getEmail()).isEqualTo("mod@example.com");
        assertThat(restored.getLanguage()).isEqualTo("de");
        assertThat(restored.getTwoFactorTokenHash()).isNull();
        assertThat(restored.getTwoFactorSessionIp()).isNull();
        assertThat(restored.getRoleId()).isNull();
    }

    private static PunishmentType type(int ordinal, String name) {
        return PunishmentType.builder().id(ordinal).ordinal(ordinal).name(name).category("Social").build();
    }
}
