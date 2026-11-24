package gg.modl.backend.staff.service;

import java.util.Locale;
import java.util.stream.IntStream;
import java.util.UUID;
import java.util.ArrayList;
import gg.modl.backend.auth.password.AccountPasswords;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.settings.SignInSettingsStub;
import gg.modl.backend.staff.dto.request.CreateStaffRequest;
import gg.modl.backend.staff.dto.request.AssignMinecraftPlayerRequest;
import gg.modl.backend.role.data.Permission;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.auth.link.PanelLoginToken;
import static org.mockito.ArgumentMatchers.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.dto.request.ReorderRolesRequest;
import gg.modl.backend.role.dto.request.RoleRequest;
import gg.modl.backend.role.dto.response.RoleResponse;
import gg.modl.backend.role.repository.JooqStaffRoleRepository;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.InviteStaffRequest;
import gg.modl.backend.staff.dto.response.InviteResultResponse;
import gg.modl.backend.staff.repository.JooqInvitationRepository;
import gg.modl.backend.staff.repository.JooqStaffRepository;
import gg.modl.backend.staff.repository.StaffPorts;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.io.TempDir;

class StaffAccessUnitsContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String EMAIL = "helper@example.com";
    private static final String TOKEN = "invitation-token-0123456789";
    private static final String MINECRAFT_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_MINECRAFT_UUID = "11111111-2222-3333-4444-555555555555";
    private static final int CALLERS = 8;
    private static final PerformerAuthority OWNER = new PerformerAuthority(OWNER_EMAIL, null, true, true);
    private static final PerformerAuthority ADMIN = new PerformerAuthority("admin@example.com", "admin", false, true);

    @TempDir
    Path dataDirectory;

    private final Server server = TestServers.server("Test", OWNER_EMAIL);

    @SqlContractTest
    void acceptingConsumesTheInvitationAndCreatesOneStaffRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.invite(EMAIL, "helper");

            Staff joined = access.invitationService.acceptInvitation(server, TOKEN, null);

            assertThat(access.ports.staff().findByEmail(EMAIL)).contains(joined);
            assertThat(joined.getUsername()).isEqualTo("helper");
            assertThat(joined.getRoleId()).isEqualTo("helper");
            assertThat(joined.getLanguage()).isEqualTo("de");
            assertThat(access.ports.invitations().countActive(NOW)).isZero();
            assertThat(access.ports.staff().countAll()).isEqualTo(1);
            verify(access.cache, times(1)).evict(server, EMAIL);
            verify(access.auditor, times(1)).recordStaffAction(server, EMAIL, "Accepted a staff invitation and signed in");
        }
    }

    @SqlContractTest
    void simultaneousAcceptsCreateOneStaffMember(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.invite(EMAIL, "helper");

            List<Boolean> accepted = StaffPorts.simultaneously(CALLERS, caller -> {
                try {
                    access.invitationService.acceptInvitation(server, TOKEN, null);
                    return true;
                } catch (ValidationException alreadyUsed) {
                    return false;
                }
            });

            assertThat(accepted).containsOnlyOnce(true);
            assertThat(access.ports.staff().countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aFailedAcceptLeavesTheInvitationRedeemable(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.invite(EMAIL, "helper");
            when(access.generalSettings.getGeneralSettings(server)).thenThrow(new IllegalStateException("settings unavailable"));

            assertThatThrownBy(() -> access.invitationService.acceptInvitation(server, TOKEN, null)).isInstanceOf(IllegalStateException.class);

            assertThat(access.ports.invitations().countActive(NOW)).isEqualTo(1);
            assertThat(access.ports.staff().countAll()).isZero();
            verify(access.cache, never()).evict(any(), anyString());
        }
    }

    @SqlContractTest
    void acceptingForAnExistingStaffMemberSpendsTheInvitation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.invite(EMAIL, "helper");
            access.ports.staff().insert(StaffPorts.member(EMAIL, "existing"));

            assertThatThrownBy(() -> access.invitationService.acceptInvitation(server, TOKEN, null)).isInstanceOf(ConflictException.class);

            assertThat(access.ports.invitations().countActive(NOW)).isZero();
            assertThat(access.ports.staff().countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void anInvitationNeverSignsInAsTheOwner(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.invite(OWNER_EMAIL, "helper");

            assertThatThrownBy(() -> access.invitationService.acceptInvitation(server, TOKEN, null)).isInstanceOf(ConflictException.class);

            assertThat(access.ports.invitations().countActive(NOW)).isZero();
            assertThat(access.ports.staff().countAll()).isZero();
        }
    }

    @SqlContractTest
    void anInvitationForADeletedOrSuperAdminRoleIsSpentWithoutAStaffRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.invite(EMAIL, "deleted");
            assertThatThrownBy(() -> access.invitationService.acceptInvitation(server, TOKEN, null)).isInstanceOf(ValidationException.class);

            access.invite(EMAIL, "super-admin");
            assertThatThrownBy(() -> access.invitationService.acceptInvitation(server, TOKEN, null)).isInstanceOf(ForbiddenException.class);

            assertThat(access.ports.invitations().countActive(NOW)).isZero();
            assertThat(access.ports.staff().countAll()).isZero();
        }
    }

    @SqlContractTest
    void aGeneratedUsernameAvoidsCollisionsWithinTheColumnLimit(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            String localPart = "a".repeat(70);
            access.ports.staff().insert(StaffPorts.member("other@example.com", "a".repeat(64)));
            access.invite(localPart + "@example.com", "helper");

            Staff joined = access.invitationService.acceptInvitation(server, TOKEN, null);

            assertThat(joined.getUsername()).isEqualTo("a".repeat(63) + "1");
        }
    }

    @SqlContractTest
    void sendingStoresOnlyAHashAndRefusesASecondPendingInvitation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            InviteStaffRequest request = new InviteStaffRequest(EMAIL, null, "Helper");

            InviteResultResponse first = access.invitationService.sendInvitations(server, request, OWNER);
            InviteResultResponse second = access.invitationService.sendInvitations(server, request, OWNER);

            assertThat(first.success()).containsExactly(EMAIL);
            assertThat(second.success()).isEmpty();
            assertThat(access.ports.invitations().findActive(NOW)).singleElement().satisfies(stored -> {
                assertThat(stored.getTokenHash()).isEqualTo(access.codec.hash(TOKEN)).isNotEqualTo(TOKEN);
                assertThat(stored.getExpiresAt()).isEqualTo(NOW.plusSeconds(86_400));
            });
        }
    }

    @SqlContractTest
    void eachInvitedAddressIsValidatedOnItsOwn(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            String growsPastTheColumn = "\u0130".repeat(127) + "@example.com";
            InviteStaffRequest request = new InviteStaffRequest(null,
                List.of("  Helper@Example.com ", "not an address", "a".repeat(250) + "@example.com", growsPastTheColumn), "Helper");

            InviteResultResponse result = access.invitationService.sendInvitations(server, request, OWNER);

            assertThat(result.success()).containsExactly(EMAIL);
            assertThat(result.failed()).hasSize(3).extracting(InviteResultResponse.FailedInvite::reason)
                .containsOnly("A valid email address is required.");
            assertThat(access.ports.invitations().findActive(NOW)).extracting(Invitation::getEmail).containsExactly(EMAIL);
        }
    }

    @SqlContractTest
    void oneRequestCannotInviteMoreThanTheListLimit(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            List<String> addresses = IntStream.range(0, 51).mapToObj(index -> "staff" + index + "@example.com").toList();

            assertThatThrownBy(() -> access.invitationService.sendInvitations(server, new InviteStaffRequest(null, addresses, "Helper"), OWNER))
                .isInstanceOf(ValidationException.class);

            assertThat(access.ports.invitations().countActive(NOW)).isZero();
            verify(access.emailService, never()).sendStaffInviteEmail(anyString(), any(), anyString(), any(), any(ValidityPeriod.class));
        }
    }

    @SqlContractTest
    void anUndeliveredInvitationIsRemoved(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            doThrow(new IllegalStateException("smtp down")).when(access.emailService)
                .sendStaffInviteEmail(anyString(), any(), anyString(), any(), any(ValidityPeriod.class));

            InviteResultResponse result = access.invitationService.sendInvitations(
                server, new InviteStaffRequest(EMAIL, null, "Helper"), OWNER);

            assertThat(result.failed()).singleElement()
                .isEqualTo(new InviteResultResponse.FailedInvite(EMAIL, "Failed to send invitation email."));
            assertThat(access.ports.invitations().countActive(NOW)).isZero();
        }
    }

    @SqlContractTest
    void resendAndRevokeKeepTheRankRule(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.admin();
            Invitation peer = access.invite(EMAIL, "admin");

            assertThatThrownBy(() -> access.invitationService.resendInvitation(server, peer.getId(), ADMIN))
                .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> access.staffService.deleteStaff(server, peer.getId(), ADMIN))
                .isInstanceOf(ForbiddenException.class);

            assertThat(access.ports.invitations().findById(peer.getId())).contains(peer);
            assertThat(access.staffService.deleteStaff(server, peer.getId(), OWNER)).isTrue();
            assertThat(access.ports.invitations().findById(peer.getId())).isEmpty();
        }
    }

    @SqlContractTest
    void removingStaffOffboardsInTheSameUnit(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            AuthRepositories auth = AuthRepositories.on(database);
            Staff member = StaffPorts.member(EMAIL, "helper");
            access.ports.staff().insert(member);
            auth.sessions().insert(new PanelSession("a".repeat(64), EMAIL, "key-hash", null, null, NOW, NOW.plusSeconds(60),
                AuthMethod.EMAIL_CODE, false));

            assertThat(access.staffService.deleteStaff(server, member.getId(), OWNER)).isTrue();

            assertThat(access.ports.staff().findById(member.getId())).isEmpty();
            assertThat(auth.sessions().findByEmail(EMAIL, NOW)).isEmpty();
            verify(access.cache, times(1)).evict(server, EMAIL);
        }
    }

    @SqlContractTest
    void aFailedOffboardingKeepsTheStaffRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            AccountOffboarding offboarding = mock(AccountOffboarding.class);
            doThrow(new IllegalStateException("offboarding failed")).when(offboarding).revokeAllAccess(server, EMAIL);
            Fixture access = fixture(database, offboarding);
            Staff member = StaffPorts.member(EMAIL, "helper");
            access.ports.staff().insert(member);

            assertThatThrownBy(() -> access.staffService.deleteStaff(server, member.getId(), OWNER))
                .isInstanceOf(IllegalStateException.class);

            assertThat(access.ports.staff().findById(member.getId())).isPresent();
            verify(access.cache, never()).evict(any(), anyString());
        }
    }

    @SqlContractTest
    void theOwnerLinkStoresARowEvenWhenTheOwnerUsernameIsTaken(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.ports.staff().insert(StaffPorts.member(EMAIL, "Admin"));

            MinecraftLinkOutcome outcome = access.minecraftStaffService.linkOwnMinecraftAccount(
                server, OWNER_EMAIL, MINECRAFT_UUID.toUpperCase(Locale.ROOT), "OwnerMc");

            assertThat(outcome).isEqualTo(MinecraftLinkOutcome.LINKED);
            assertThat(access.ports.staff().findByAssignedMinecraftUuid(MINECRAFT_UUID)).get().satisfies(owner -> {
                assertThat(owner.getEmail()).isEqualTo(OWNER_EMAIL);
                assertThat(owner.getUsername()).isEqualTo("Admin1");
                assertThat(owner.getRoleId()).isEqualTo(RoleAuthorization.SUPER_ADMIN_ROLE_ID);
            });
            assertThat(access.minecraftStaffService.linkOwnMinecraftAccount(server, EMAIL, MINECRAFT_UUID, "Other"))
                .isEqualTo(MinecraftLinkOutcome.ASSIGNED_TO_OTHER);
        }
    }

    @SqlContractTest
    void paddedRoleIdsCannotSlipPastTheSuperAdminAndRankRules(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.admin();
            List<StaffRole> before = access.ports.roles().findAllOrdered();

            assertThat(access.roleService.deleteRole(server, "super-admin ", OWNER)).isFalse();
            assertThat(access.roleService.updateRole(server, "super-admin ", new RoleRequest("Taken", "Taken", List.of()), OWNER))
                .isEmpty();
            assertThat(access.roleService.updateRolePermissions(server, "super-admin ", List.of(), OWNER)).isFalse();
            access.roleService.reorderRoles(server, new ReorderRolesRequest(
                List.of(new ReorderRolesRequest.RoleOrderItem("super-admin ", 9))), ADMIN);

            assertThat(access.ports.roles().findAllOrdered()).isEqualTo(before);
        }
    }

    @SqlContractTest
    void aRoleInUseCannotBeDeleted(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.ports.staff().insert(StaffPorts.member(EMAIL, "helper"));

            assertThatThrownBy(() -> access.roleService.deleteRole(server, "helper", OWNER)).isInstanceOf(ConflictException.class);

            assertThat(access.ports.roles().findById("helper")).isPresent();
            assertThat(access.roleService.deleteRole(server, "moderator", OWNER)).isTrue();
        }
    }

    @SqlContractTest
    void seedingIsIdempotentAndKeepsEdits(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.roleService.updateRole(server, "helper", new RoleRequest("Trainee", "Edited", List.of("ticket.view.all")), OWNER);
            List<StaffRole> edited = access.ports.roles().findAllOrdered();

            access.roleService.createDefaultRoles(server);

            assertThat(access.ports.roles().findAllOrdered()).isEqualTo(edited);
        }
    }

    @SqlContractTest
    void aRoleNeedsNoDescription(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);

            RoleResponse created = access.roleService.createRole(server, new RoleRequest("Builder", "", List.of("ticket.view.all")), OWNER);
            access.roleService.updateRole(server, "helper", new RoleRequest("Helper", "", List.of("ticket.view.all")), OWNER);

            assertThat(access.ports.roles().findById(created.id())).get().extracting(StaffRole::getDescription).isEqualTo("");
            assertThat(access.ports.roles().findById("helper")).get().extracting(StaffRole::getDescription).isEqualTo("");
        }
    }

    @SqlContractTest
    void theHelperViewPermissionCanBeRemovedForGood(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            assertThat(access.permissionService.hasPermission(server, "helper", "punishment.view")).isTrue();

            access.roleService.updateRole(server, "helper",
                new RoleRequest("Helper", "No viewing", List.of("player.note", "player.warn")), OWNER);
            access.roleService.createDefaultRoles(server);

            assertThat(access.ports.roles().findById("helper")).get().satisfies(helper ->
                assertThat(helper.getPermissions()).containsExactlyInAnyOrder("player.note", "player.warn"));
        }
    }

    @SqlContractTest
    void applyPermissionsAreKeyedByOrdinalSoARenameChangesNoRole(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.grantToHelper("punishment.apply.20", "ticket.view.all");
            List<StaffRole> before = access.ports.roles().findAllOrdered();
            when(access.punishmentTypes.getPunishmentTypes(server)).thenReturn(List.of(
                PunishmentType.builder().name("Blacklist").ordinal(20).build(),
                PunishmentType.builder().name("5").ordinal(21).build()));

            assertThat(access.permissionService.getPunishmentPermissions(server)).extracting(Permission::id)
                .containsExactly("punishment.apply.20", "punishment.apply.21");
            assertThat(access.ports.roles().findAllOrdered()).isEqualTo(before);
            assertThat(access.permissionService.hasPermission(server, "helper", "punishment.apply.20")).isTrue();
            assertThat(access.permissionService.hasPermission(server, "helper", "punishment.apply.5")).isFalse();
            assertThat(access.permissionService.hasPermission(server, "helper", "punishment.apply.2")).isFalse();
        }
    }

    @SqlContractTest
    void aMemberManagerCannotTakeOverTheOwnerOrAnEqualRankThroughAMinecraftAccount(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.admin();
            Staff peer = StaffPorts.member("peer@example.com", "peer");
            peer.setRoleId("admin");
            access.ports.staff().insert(peer);
            access.knownPlayer(MINECRAFT_UUID, "Takeover");
            AssignMinecraftPlayerRequest takeover = new AssignMinecraftPlayerRequest(MINECRAFT_UUID, null);

            for (String victim : List.of(OWNER_EMAIL, "peer@example.com", ADMIN.email())) {
                assertThatThrownBy(() -> access.minecraftStaffService.assignMinecraftPlayer(server, victim, takeover, ADMIN))
                    .as(victim).isInstanceOf(ForbiddenException.class);
            }
            assertThat(access.ports.staff().findByAssignedMinecraftUuid(MINECRAFT_UUID)).isEmpty();
            assertThat(access.ports.staff().findByEmail(OWNER_EMAIL)).isEmpty();

            access.moderator();
            assertThat(access.minecraftStaffService.assignMinecraftPlayer(server, EMAIL, takeover, ADMIN)).isPresent();
            assertThat(access.ports.staff().findByAssignedMinecraftUuid(MINECRAFT_UUID)).get()
                .extracting(Staff::getEmail).isEqualTo(EMAIL);
        }
    }

    @SqlContractTest
    void reassigningAMinecraftAccountEndsTheSecondFactorAndLoginLinksOfTheOldOne(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            AuthRepositories auth = AuthRepositories.on(database);
            Staff moderator = access.moderator();
            access.knownPlayer(MINECRAFT_UUID, "First");
            access.knownPlayer(OTHER_MINECRAFT_UUID, "Second");
            access.minecraftStaffService.assignMinecraftPlayer(server, EMAIL, new AssignMinecraftPlayerRequest(MINECRAFT_UUID, null), OWNER);
            access.ports.staff().createTwoFactorToken(MINECRAFT_UUID, "token-hash", "203.0.113.7", NOW);
            access.ports.staff().activateTwoFactorSession(moderator.getId(), "token-hash", "203.0.113.7", NOW.plusSeconds(86_400));
            auth.loginTokens().insert(PanelLoginToken.builder().id("link-1").tokenHash("link-hash").identityEmail(EMAIL)
                .minecraftUuid(MINECRAFT_UUID).expiresAt(NOW.plusSeconds(120)).createdAt(NOW).build());

            access.minecraftStaffService.assignMinecraftPlayer(server, EMAIL,
                new AssignMinecraftPlayerRequest(OTHER_MINECRAFT_UUID, null), OWNER);

            assertThat(access.ports.staff().findById(moderator.getId())).get().satisfies(stored -> {
                assertThat(stored.getAssignedMinecraftUuid()).isEqualTo(OTHER_MINECRAFT_UUID);
                assertThat(stored.getTwoFactorSessionIp()).isNull();
                assertThat(stored.getTwoFactorSessionExpiresAt()).isNull();
                assertThat(stored.isTwoFactorPendingDelivery()).isFalse();
            });
            assertThat(auth.loginTokens().consumeActive("link-hash", NOW)).isEmpty();
            verify(access.auditor).recordStaffAction(eq(server), eq(OWNER_EMAIL), contains(OTHER_MINECRAFT_UUID));
        }
    }

    @SqlContractTest
    void staffCreatedDirectlyCanNeverBeTheOwnerOrTakeAReservedName(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);
            access.admin();

            for (String ownerSpelling : List.of(OWNER_EMAIL, " Owner@Example.COM ")) {
                assertThatThrownBy(() -> access.staffService.createStaff(server,
                    new CreateStaffRequest(ownerSpelling, "owner-helper", "Helper"), ADMIN)).isInstanceOf(ConflictException.class);
            }
            for (String reserved : List.of("Console", "SYSTEM", "A.I.", "Ｃｏｎｓｏｌｅ")) {
                assertThatThrownBy(() -> access.staffService.createStaff(server,
                    new CreateStaffRequest(EMAIL, reserved, "Helper"), ADMIN)).as(reserved).isInstanceOf(ValidationException.class);
            }
            assertThat(access.ports.staff().countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void theOwnersProfileIsStoredOnItsFirstSaveAndReservedNamesAreRefused(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Fixture access = fixture(database);

            assertThat(access.profileService.updateProfile(server, OWNER_EMAIL, null, null, null)).isEmpty();
            assertThat(access.profileService.updateProfile(server, OWNER_EMAIL, "TheOwner", "fr", "YYYY-MM-DD")).isPresent();

            assertThat(access.ports.staff().findByEmail(OWNER_EMAIL)).get().satisfies(owner -> {
                assertThat(owner.getUsername()).isEqualTo("TheOwner");
                assertThat(owner.getLanguage()).isEqualTo("fr");
                assertThat(owner.getDateFormat()).isEqualTo("YYYY-MM-DD");
                assertThat(owner.getRoleId()).isEqualTo(RoleAuthorization.SUPER_ADMIN_ROLE_ID);
            });
            assertThatThrownBy(() -> access.profileService.updateProfile(server, OWNER_EMAIL, " system ", null, null))
                .isInstanceOf(ValidationException.class);
            assertThat(access.profileService.updateProfile(server, "stranger@example.com", "Stranger", null, null)).isEmpty();
            assertThat(access.ports.staff().countAll()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aProfileSaveRacingADemotionKeepsTheDemotion(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            Staff moderator = access.moderator();

            Optional<Staff> saved = StaffPorts.whileUncommitted(database,
                () -> access.staffService.updateStaffRole(server, moderator.getId(), "Helper", OWNER),
                () -> access.profileService.updateProfile(server, EMAIL, "renamed", "fr", null));

            assertThat(saved).get().extracting(Staff::getRoleId).isEqualTo("helper");
            assertThat(access.ports.staff().findById(moderator.getId())).get().satisfies(stored -> {
                assertThat(stored.getRoleId()).isEqualTo("helper");
                assertThat(stored.getUsername()).isEqualTo("renamed");
                assertThat(stored.getLanguage()).isEqualTo("fr");
            });
        }
    }

    @SqlContractTest
    void aDemotionRacingAProfileSaveKeepsTheProfile(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            Staff moderator = access.moderator();

            StaffPorts.whileUncommitted(database,
                () -> access.profileService.updateProfile(server, EMAIL, "renamed", "fr", null),
                () -> access.staffService.updateStaffRole(server, moderator.getId(), "Helper", OWNER));

            assertThat(access.ports.staff().findById(moderator.getId())).get().satisfies(stored -> {
                assertThat(stored.getRoleId()).isEqualTo("helper");
                assertThat(stored.getUsername()).isEqualTo("renamed");
                assertThat(stored.getLanguage()).isEqualTo("fr");
            });
        }
    }

    @SqlContractTest
    void aDemotionRacingAPromotionCannotOverwriteIt(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.admin();
            Staff moderator = access.moderator();

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.staffService.updateStaffRole(server, moderator.getId(), "Admin", OWNER),
                () -> isRefused(() -> access.staffService.updateStaffRole(server, moderator.getId(), "Helper", ADMIN)));

            assertThat(refused).isTrue();
            assertThat(access.ports.staff().findById(moderator.getId())).get().extracting(Staff::getRoleId).isEqualTo("admin");
        }
    }

    @SqlContractTest
    void aRemovalRacingAPromotionIsRefused(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.admin();
            Staff moderator = access.moderator();

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.staffService.updateStaffRole(server, moderator.getId(), "Admin", OWNER),
                () -> isRefused(() -> access.staffService.deleteStaff(server, moderator.getId(), ADMIN)));

            assertThat(refused).isTrue();
            assertThat(access.ports.staff().findById(moderator.getId())).isPresent();
        }
    }

    @SqlContractTest
    void aPerformerDemotedMidRequestCannotUseTheirOldRank(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            Staff admin = access.admin();
            Staff moderator = access.moderator();

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.staffService.updateStaffRole(server, admin.getId(), "Helper", OWNER),
                () -> isRefused(() -> access.staffService.updateStaffRole(server, moderator.getId(), "Helper", ADMIN)));

            assertThat(refused).isTrue();
            assertThat(access.ports.staff().findById(moderator.getId())).get().extracting(Staff::getRoleId).isEqualTo("moderator");
        }
    }

    @SqlContractTest
    void aGrantRacingThePromotionOfThatRoleIsRefused(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.admin();
            Staff helper = StaffPorts.member(EMAIL, "helper");
            access.ports.staff().insert(helper);

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.roleService.reorderRoles(server, order("moderator", 1), OWNER),
                () -> isRefused(() -> access.staffService.updateStaffRole(server, helper.getId(), "Moderator", ADMIN)));

            assertThat(refused).isTrue();
            assertThat(access.ports.staff().findById(helper.getId())).get().extracting(Staff::getRoleId).isEqualTo("helper");
        }
    }

    @SqlContractTest
    void aReorderRacingThePromotionOfItsTargetIsRefused(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.admin();

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.roleService.reorderRoles(server, order("moderator", 1), OWNER),
                () -> isRefused(() -> access.roleService.reorderRoles(server, order("moderator", 5), ADMIN)));

            assertThat(refused).isTrue();
            assertThat(access.ports.roles().findById("moderator")).get().extracting(StaffRole::getOrder).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aReorderRacingTheDemotionOfThePerformersRoleIsRefused(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.admin();

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.roleService.reorderRoles(server, order("admin", 8), OWNER),
                () -> isRefused(() -> access.roleService.reorderRoles(server, order("moderator", 5), ADMIN)));

            assertThat(refused).isTrue();
            assertThat(access.ports.roles().findById("moderator")).get().extracting(StaffRole::getOrder).isEqualTo(2);
        }
    }

    @SqlContractTest
    void anInvitationRacingThePromotionOfItsRoleIsRefused(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.admin();
            InviteStaffRequest request = new InviteStaffRequest(EMAIL, null, "Moderator");

            InviteResultResponse result = StaffPorts.whileUncommitted(database,
                () -> access.roleService.reorderRoles(server, order("moderator", 1), OWNER),
                () -> access.invitationService.sendInvitations(server, request, ADMIN));

            assertThat(result.success()).isEmpty();
            assertThat(access.ports.invitations().countActive(NOW)).isZero();
        }
    }

    @SqlContractTest
    void aRoleDeletionRacingItsAssignmentSeesTheAssignment(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            Staff helper = StaffPorts.member(EMAIL, "helper");
            access.ports.staff().insert(helper);

            boolean refused = StaffPorts.whileUncommitted(database,
                () -> access.staffService.updateStaffRole(server, helper.getId(), "Moderator", OWNER),
                () -> {
                    try {
                        access.roleService.deleteRole(server, "moderator", OWNER);
                        return false;
                    } catch (ConflictException inUse) {
                        return true;
                    }
                });

            assertThat(refused).isTrue();
            assertThat(access.ports.roles().findById("moderator")).isPresent();
        }
    }

    @SqlContractTest
    void oppositeReordersTakeTheirLocksInOneOrderAndNeverRetry(DialectTarget target) throws Exception {
        AtomicInteger retries = new AtomicInteger();
        WriteAttemptHook countRetries = attempt -> {
            if (attempt > 1) {
                retries.incrementAndGet();
            }
        };
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, countRetries)) {
            Fixture access = fixture(database);
            List<ReorderRolesRequest.RoleOrderItem> forwards = List.of(
                new ReorderRolesRequest.RoleOrderItem("admin", 1),
                new ReorderRolesRequest.RoleOrderItem("helper", 3),
                new ReorderRolesRequest.RoleOrderItem("moderator", 2));

            StaffPorts.simultaneously(CALLERS, caller -> {
                access.roleService.reorderRoles(server,
                    new ReorderRolesRequest(caller % 2 == 0 ? forwards : forwards.reversed()), OWNER);
                return caller;
            });

            assertThat(retries).hasValue(0);
        }
    }

    @SqlContractTest
    void theStaffListTakesTheSameStatementsForAnyNumberOfStaff(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Fixture access = fixture(database);
            access.ports.staff().insert(StaffPorts.member("first@example.com", "first"));
            int forOne = statementsToListStaff(database, access);
            database.unitOfWork().write(() -> {
                for (int index = 0; index < 40; index++) {
                    Staff member = StaffPorts.member(index + "@example.com", "member" + index);
                    member.setRoleId("custom-" + index);
                    access.ports.staff().insert(member);
                }
            });

            assertThat(statementsToListStaff(database, access)).isEqualTo(forOne);
        }
    }

    private int statementsToListStaff(SqlTestDatabase database, Fixture access) {
        AtomicInteger statements = new AtomicInteger();
        DSLContext counted = DSL.using(database.dsl().configuration()
            .deriveAppending(ExecuteListener.onExecuteStart(context -> statements.incrementAndGet())));
        StaffRoleRepository countedRoles =
            new JooqStaffRoleRepository(counted, database.unitOfWork(), database.insertIfAbsent(), database.dialect());
        StaffRepository countedStaff =
            new JooqStaffRepository(counted, database.unitOfWork(), database.insertIfAbsent(), database.jsonColumns(),
                database.dialect());
        StaffService counting = new StaffService(
            new JooqInvitationRepository(counted, database.unitOfWork()),
            countedStaff,
            new PermissionService(countedRoles, mock(PunishmentTypeService.class), countedStaff),
            access.roleAuthorization,
            mock(RankLocks.class),
            mock(AccountOffboarding.class),
            access.generalSettings,
            new StaffChanges(database.unitOfWork(), access.cache, mock(ServerTimestampService.class)),
            database.unitOfWork(),
            database.clock());

        counting.getAllStaff(server);
        return statements.get();
    }

    private static boolean isRefused(Runnable write) {
        try {
            write.run();
            return false;
        } catch (ForbiddenException refused) {
            return true;
        }
    }

    private static ReorderRolesRequest order(String roleId, int order) {
        return new ReorderRolesRequest(List.of(new ReorderRolesRequest.RoleOrderItem(roleId, order)));
    }

    private Fixture fixture(SqlTestDatabase database) {
        AuthRepositories auth = AuthRepositories.on(database);
        return fixture(database, new AccountOffboarding(auth.sessions(), auth.credentials(), auth.challenges(), auth.authCodes(),
            auth.loginTokens(), auth.linkCodes(), auth.passwords(), auth.totp(), auth.resetTokens(), database.unitOfWork(), database.clock()));
    }

    private Fixture fixture(SqlTestDatabase database, AccountOffboarding offboarding) {
        Fixture fixture = new Fixture(database, offboarding);
        fixture.roleService.createDefaultRoles(server);
        when(fixture.punishmentTypes.getPunishmentTypes(server)).thenReturn(List.of(
            PunishmentType.builder().name("Old Name").ordinal(20).build(),
            PunishmentType.builder().name("New Name").ordinal(21).build()));
        when(fixture.generalSettings.getGeneralSettings(eq(server)))
            .thenReturn(GeneralSettings.builder().defaultLanguage("de").build());
        return fixture;
    }

    private static final class Fixture {
        private final StaffPorts ports;
        private final OneTimeCodeCodec codec = new OneTimeCodeCodec(() -> "invitation-test-secret");
        private final StaffLookupCache cache = mock(StaffLookupCache.class);
        private final GeneralSettingsService generalSettings = mock(GeneralSettingsService.class);
        private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
        private final EmailService emailService = mock(EmailService.class);
        private final PunishmentTypeService punishmentTypes = mock(PunishmentTypeService.class);
        private final PlayerRepository players = mock(PlayerRepository.class);
        private final PermissionService permissionService;
        private final RoleAuthorization roleAuthorization;
        private final RoleService roleService;
        private final StaffService staffService;
        private final InvitationService invitationService;
        private final StaffProfileService profileService;
        private final MinecraftStaffService minecraftStaffService;

        private Fixture(SqlTestDatabase database, AccountOffboarding offboarding) {
            ports = StaffPorts.on(database);
            ServerTimestampService timestamps = mock(ServerTimestampService.class);
            permissionService = new PermissionService(ports.roles(), punishmentTypes, ports.staff());
            roleAuthorization = new RoleAuthorization(permissionService, ports.staff(), cache);
            RankLocks rankLocks = new RankLocks(ports.staff(), ports.roles());
            roleService = new RoleService(ports.roles(), ports.staff(), permissionService, roleAuthorization, rankLocks, timestamps,
                database.unitOfWork(), database.clock());
            StaffChanges changes = new StaffChanges(database.unitOfWork(), cache, timestamps);
            staffService = new StaffService(ports.invitations(), ports.staff(), permissionService, roleAuthorization, rankLocks, offboarding,
                generalSettings, changes, database.unitOfWork(), database.clock());
            OwnerStaffRecords owners = new OwnerStaffRecords(ports.staff(), database.clock());
            profileService = new StaffProfileService(ports.staff(), owners, changes, database.unitOfWork(), database.clock());
            IdGenerator idGenerator = mock(IdGenerator.class);
            when(idGenerator.generateToken()).thenReturn(TOKEN);
            invitationService = new InvitationService(ports.staff(), ports.invitations(), staffService, emailService, idGenerator,
                codec, mock(PublicUrls.class), mock(EmailCapability.class), permissionService, roleAuthorization, rankLocks, auditor,
                mock(StaffCredentialRepository.class), mock(AccountPasswords.class), SignInSettingsStub.emailCode(),
                database.unitOfWork(), database.clock());
            MinecraftAccountAssignments assignments = new MinecraftAccountAssignments(ports.staff(), owners, offboarding, changes,
                auditor, database.unitOfWork(), database.clock());
            minecraftStaffService = new MinecraftStaffService(ports.staff(), players, mock(PunishmentRepository.class),
                mock(PlayerService.class), permissionService, roleAuthorization, staffService, rankLocks, assignments,
                database.unitOfWork(), database.clock());
        }

        private Staff admin() {
            Staff admin = StaffPorts.member(ADMIN.email(), "admin");
            admin.setRoleId("admin");
            ports.staff().insert(admin);
            return admin;
        }

        private Staff moderator() {
            Staff moderator = StaffPorts.member(EMAIL, "original");
            moderator.setRoleId("moderator");
            ports.staff().insert(moderator);
            return moderator;
        }

        private void knownPlayer(String minecraftUuid, String username) {
            Player player = Player.builder()
                .minecraftUuid(UUID.fromString(minecraftUuid))
                .usernames(new ArrayList<>(List.of(new UsernameEntry(username, NOW))))
                .build();
            when(players.findByMinecraftUuid(minecraftUuid, PlayerParts.USERNAMES)).thenReturn(Optional.of(player));
        }

        private void grantToHelper(String... permissions) {
            StaffRole helper = ports.roles().findById("helper").orElseThrow();
            helper.setPermissions(List.of(permissions));
            ports.roles().save(helper);
        }

        private Invitation invite(String email, String roleId) {
            Invitation invitation = Invitation.builder()
                .email(email)
                .roleId(roleId)
                .tokenHash(codec.hash(TOKEN))
                .expiresAt(NOW.plusSeconds(3600))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
            ports.invitations().insert(invitation);
            return invitation;
        }
    }
}
