package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.auth.controller.PanelAuthController;
import gg.modl.backend.auth.controller.PasswordAuthController;
import gg.modl.backend.auth.controller.SecondFactorSignInController;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.controller.WebAuthnController;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.authorization.PanelAccessPolicyResolver;
import gg.modl.backend.infrastructure.authorization.PanelHandlerMappingTestSupport;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.support.TestServers;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class PanelAuthorizationMatrixTest {

    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String STAFF_EMAIL = "staff@example.com";
    private static final String SUPER_ADMIN_EMAIL = "admin@example.com";
    private static final String ROLE = "helper";
    private static final String DENY_BODY =
        "{\"success\":false,\"status\":403,\"error\":\"Insufficient permissions\",\"message\":\"Insufficient permissions\",\"code\":\"PERMISSION_DENIED\"}";
    private static final String UNAUTHENTICATED_BODY =
        "{\"success\":false,\"status\":401,\"error\":\"Authentication required\",\"message\":\"Authentication required\",\"code\":\"UNAUTHENTICATED\"}";
    private static final List<String> ENCODED_SUPER_ADMIN_PATHS = List.of(
        "/%761/panel/settings/api-keys/panel/reveal",
        "/v1/%70anel/settings/api-keys/panel/reveal",
        "/%76%31/%70%61%6e%65%6c/settings/api-keys/panel/reveal",
        "/v1;x=y/panel;a=b/settings/api-keys/panel/reveal"
    );

    private enum Kind { PERMISSION, PERMISSION_PREFIX, PERMIT, PLAYER_READ, APPEAL_REPLY, SUPER_ADMIN }

    private record Row(String method, String path, Kind kind, String permission) {
        static Row permission(String method, String path, String permission) {
            return new Row(method, path, Kind.PERMISSION, permission);
        }

        static Row permissionPrefix(String method, String path, String prefix) {
            return new Row(method, path, Kind.PERMISSION_PREFIX, prefix);
        }

        static Row permit(String method, String path) {
            return new Row(method, path, Kind.PERMIT, null);
        }

        static Row playerRead(String path) {
            return new Row("GET", path, Kind.PLAYER_READ, null);
        }

        static Row appealReply(String path) {
            return new Row("POST", path, Kind.APPEAL_REPLY, null);
        }

        static Row superAdminOnly(String method, String path) {
            return new Row(method, path, Kind.SUPER_ADMIN, null);
        }
    }

    private static final List<Row> MATRIX = List.of(
        Row.permission("GET", "/v1/panel/staff", "admin.staff.manage.members"),
        Row.permission("POST", "/v1/panel/staff", "admin.staff.manage.members"),
        Row.permission("GET", "/v1/panel/roles", "admin.staff.manage.roles"),
        Row.permission("POST", "/v1/panel/roles", "admin.staff.manage.roles"),
        Row.permission("GET", "/v1/panel/dashboard/metrics", "admin.audit.view.dashboard"),
        Row.permission("GET", "/v1/panel/analytics/tickets", "admin.audit.view.analytics"),
        Row.permission("GET", "/v1/panel/audit/staff-performance", "admin.audit.view.logs"),
        Row.permission("GET", "/v1/panel/audit/punishments", "admin.audit.view.logs"),
        Row.permission("GET", "/v1/panel/audit/punishments/types", "admin.audit.view.logs"),
        Row.superAdminOnly("POST", "/v1/panel/audit/punishments/p1/rollback"),
        Row.superAdminOnly("POST", "/v1/panel/audit/staff/staff1/rollback-all"),
        Row.superAdminOnly("POST", "/v1/panel/audit/staff/staff1/rollback-date-range"),
        Row.superAdminOnly("POST", "/v1/panel/audit/punishments/bulk-pardon"),
        Row.superAdminOnly("POST", "/v1/panel/audit/punishments/bulk-set-expiration"),
        Row.permission("GET", "/v1/panel/logs", "admin.audit.view.logs"),
        Row.permission("POST", "/v1/panel/replays/r1/label", "punishment.modify"),
        Row.permission("POST", "/v1/panel/players/uuid1/notes", "player.note"),
        Row.permission("POST", "/v1/panel/players/uuid1/punishments/p1/notes", "punishment.modify.note"),
        Row.permission("POST", "/v1/panel/players/uuid1/punishments/p1/evidence", "punishment.modify.evidence"),
        Row.permission("POST", "/v1/panel/players/uuid1/punishments/p1/tickets", "punishment.modify.tickets"),
        Row.permission("POST", "/v1/panel/players/uuid1/punishments/p1/modifications", "punishment.modify.pardon"),
        Row.permission("POST", "/v1/panel/players", "punishment.modify"),
        Row.permission("POST", "/v1/panel/players/uuid1/usernames", "punishment.modify"),
        Row.permission("POST", "/v1/panel/players/uuid1/ips", "punishment.modify"),
        Row.permission("POST", "/v1/panel/players/uuid1/find-linked", "punishment.modify"),
        Row.permission("GET", "/v1/panel/tickets", "ticket.view.all"),
        Row.permission("POST", "/v1/panel/tickets", "ticket.reply.all"),
        Row.permission("POST", "/v1/panel/tickets/bulk", "ticket.close.all"),
        Row.permission("PATCH", "/v1/panel/tickets/t1", "ticket.view.all"),
        Row.permission("POST", "/v1/panel/tickets/t1/notes", "ticket.view.all"),
        Row.permission("POST", "/v1/panel/tickets/t1/replies", "ticket.reply.all"),
        Row.permission("GET", "/v1/panel/ticket-subscriptions", "ticket.view.all"),
        Row.permission("POST", "/v1/panel/ticket-subscriptions/updates/u1/read", "ticket.reply.all"),
        Row.permission("GET", "/v1/panel/appeals/a1", "ticket.view.all"),
        Row.permission("PATCH", "/v1/panel/appeals/a1/status", "appeal.modify"),
        Row.permission("GET", "/v1/panel/homepage-cards", "admin.settings.view.content"),
        Row.permission("POST", "/v1/panel/homepage-cards", "admin.settings.modify.content"),
        Row.permission("GET", "/v1/panel/knowledgebase/categories", "admin.settings.view.content"),
        Row.permission("POST", "/v1/panel/knowledgebase/categories", "admin.settings.modify.content"),
        Row.permit("GET", "/v1/panel/media/config"),
        Row.permit("POST", "/v1/panel/media/presign"),
        Row.permit("POST", "/v1/panel/media/confirm"),
        Row.permission("DELETE", "/v1/panel/media/db/tickets/t1/upload.png", "admin.settings.modify.content"),
        Row.permission("GET", "/v1/panel/settings/general", "admin.settings.view"),
        Row.superAdminOnly("PATCH", "/v1/panel/settings/general"),
        Row.superAdminOnly("POST", "/v1/panel/settings/upload-icon"),
        Row.permission("GET", "/v1/panel/settings/public-theme", "admin.settings.view.content"),
        Row.permission("PUT", "/v1/panel/settings/public-theme", "admin.settings.modify.content"),
        Row.permission("POST", "/v1/panel/settings/public-theme/background", "admin.settings.modify.content"),
        Row.superAdminOnly("GET", "/v1/panel/settings/public-css"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/public-css"),
        Row.permission("GET", "/v1/panel/settings/ticket-forms", "admin.settings.view"),
        Row.permission("PATCH", "/v1/panel/settings/ticket-forms", "admin.settings.modify"),
        Row.permission("GET", "/v1/panel/settings/quick-responses", "admin.settings.view"),
        Row.permission("GET", "/v1/panel/settings/quick-responses", "appeal.modify"),
        Row.permissionPrefix("GET", "/v1/panel/settings/quick-responses", "ticket."),
        Row.permission("PATCH", "/v1/panel/settings/quick-responses", "admin.settings.modify"),
        Row.permission("GET", "/v1/panel/settings/ticket-labels", "admin.settings.view"),
        Row.permission("GET", "/v1/panel/settings/ticket-labels", "appeal.modify"),
        Row.permissionPrefix("GET", "/v1/panel/settings/ticket-labels", "ticket."),
        Row.permission("PATCH", "/v1/panel/settings/ticket-labels", "admin.settings.modify"),
        Row.permission("GET", "/v1/panel/settings/status-thresholds", "admin.settings.view.punishments"),
        Row.permission("PATCH", "/v1/panel/settings/status-thresholds", "admin.settings.modify.punishments"),
        Row.permission("GET", "/v1/panel/settings/data-retention", "admin.settings.view"),
        Row.superAdminOnly("PATCH", "/v1/panel/settings/data-retention"),
        Row.permission("GET", "/v1/panel/settings/data-retention/limits", "admin.settings.view"),
        Row.superAdminOnly("PATCH", "/v1/panel/settings/data-retention/limits"),
        Row.permission("GET", "/v1/panel/settings/storage", "admin.settings.view.storage"),
        Row.permission("PATCH", "/v1/panel/settings/storage/limits", "admin.settings.modify.storage"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/storage/s3"),
        Row.superAdminOnly("POST", "/v1/panel/settings/storage/s3/test"),
        Row.permission("POST", "/v1/panel/settings/storage/switch", "admin.settings.modify.storage"),
        Row.permission("POST", "/v1/panel/settings/storage/transfer/retry", "admin.settings.modify.storage"),
        Row.superAdminOnly("POST", "/v1/panel/settings/storage/old-copies/remove"),
        Row.superAdminOnly("GET", "/v1/panel/settings/database"),
        Row.superAdminOnly("POST", "/v1/panel/settings/database/test"),
        Row.superAdminOnly("POST", "/v1/panel/settings/database/move"),
        Row.permission("GET", "/v1/panel/settings/replay-retention", "admin.settings.view"),
        Row.superAdminOnly("PATCH", "/v1/panel/settings/replay-retention"),
        Row.permission("GET", "/v1/panel/settings/ai-moderation", "admin.settings.view"),
        Row.permission("PATCH", "/v1/panel/settings/ai-moderation", "admin.settings.modify"),
        Row.permission("POST", "/v1/panel/settings/ai-dismiss-suggestion/t1", "admin.settings.modify.punishments"),
        Row.permission("GET", "/v1/panel/settings/punishment-types", "admin.settings.view.punishments"),
        Row.permission("POST", "/v1/panel/settings/punishment-types", "admin.settings.modify.punishments"),
        Row.playerRead("/v1/panel/settings/punishment-types"),
        Row.superAdminOnly("POST", "/v1/panel/settings/api-keys/panel/generate"),
        Row.superAdminOnly("GET", "/v1/panel/settings/api-keys/panel/exists"),
        Row.superAdminOnly("GET", "/v1/panel/settings/ai"),
        Row.permission("GET", "/v1/panel/settings/ai/status", "admin.settings.view"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/ai"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/ai/connections/openai"),
        Row.superAdminOnly("DELETE", "/v1/panel/settings/ai/connections/openai"),
        Row.superAdminOnly("GET", "/v1/panel/settings/player-addresses"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/player-addresses"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/player-addresses/keys/proxycheck"),
        Row.superAdminOnly("DELETE", "/v1/panel/settings/player-addresses/keys/proxycheck"),
        Row.superAdminOnly("POST", "/v1/panel/settings/player-addresses/notice/dismiss"),
        Row.superAdminOnly("POST", "/v1/panel/settings/player-addresses/choice/dismiss"),
        Row.superAdminOnly("POST", "/v1/panel/settings/player-addresses/test"),
        Row.superAdminOnly("POST", "/v1/panel/settings/ai/models"),
        Row.superAdminOnly("POST", "/v1/panel/settings/ai/test"),
        Row.permit("POST", "/v1/panel/account/minecraft-link"),
        Row.permit("DELETE", "/v1/panel/account/minecraft-link"),
        Row.permit("POST", "/v1/panel/account/two-factor/request"),
        Row.permit("POST", "/v1/panel/account/two-factor/verify"),
        Row.superAdminOnly("GET", "/v1/panel/setup"),
        Row.superAdminOnly("PUT", "/v1/panel/setup/steps/identity"),
        Row.superAdminOnly("POST", "/v1/panel/setup/public-url/check"),
        Row.superAdminOnly("GET", "/v1/panel/setup/import"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/upload"),
        Row.superAdminOnly("PUT", "/v1/panel/setup/import/upload"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/upload/complete"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/download"),
        Row.superAdminOnly("GET", "/v1/panel/setup/import/preview"),
        Row.superAdminOnly("GET", "/v1/panel/setup/import/staff"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/start"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/resume"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/discard"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/abandon"),
        Row.superAdminOnly("POST", "/v1/panel/setup/import/finish"),
        Row.superAdminOnly("GET", "/v1/panel/setup/import/report"),
        Row.superAdminOnly("GET", "/v1/panel/settings/mail"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/mail"),
        Row.superAdminOnly("DELETE", "/v1/panel/settings/mail"),
        Row.superAdminOnly("POST", "/v1/panel/settings/mail/test"),
        Row.superAdminOnly("GET", "/v1/panel/settings/sign-in"),
        Row.superAdminOnly("PUT", "/v1/panel/settings/sign-in"),
        Row.superAdminOnly("GET", "/v1/panel/staff-security"),
        Row.superAdminOnly("POST", "/v1/panel/staff-security/s1/password-reset"),
        Row.superAdminOnly("DELETE", "/v1/panel/staff-security/s1/second-factor"),
        Row.permission("GET", "/v1/panel/servers/connected", "admin.settings.view"),
        Row.superAdminOnly("GET", "/v1/panel/settings/server-keys"),
        Row.superAdminOnly("POST", "/v1/panel/settings/server-keys"),
        Row.superAdminOnly("PATCH", "/v1/panel/settings/server-keys/k1"),
        Row.superAdminOnly("DELETE", "/v1/panel/settings/server-keys/k1"),
        Row.permit("POST", "/v1/panel/players/uuid1/punishments"),
        Row.permit("POST", "/v1/panel/settings/ai-apply-punishment/t1"),
        Row.playerRead("/v1/panel/players"),
        Row.playerRead("/v1/panel/players/uuid1"),
        Row.playerRead("/v1/panel/players/uuid1/punishments/active"),
        Row.playerRead("/v1/panel/players/punishments/search"),
        Row.superAdminOnly("GET", "/v1/panel/audit/database/players"),
        Row.superAdminOnly("GET", "/v1/panel/settings/api-keys/panel/reveal"),
        Row.appealReply("/v1/panel/appeals/a1/replies")
    );

    @Test
    void authorizationMatrixIsPreservedForEveryMappedRoute() {
        List<String> failures = new ArrayList<>();

        for (Row row : MATRIX) {
            if (row.kind() == Kind.SUPER_ADMIN) {
                assertDenied(row, "holding-every-permission", staffWith(row), failures);
            } else {
                assertGranted(row, "with-permission", staffWith(row), failures);
            }
            if (row.kind() != Kind.PERMIT) {
                assertDenied(row, "without-permission", staffWithout(), failures);
            }
            assertGranted(row, "super-admin", this::signInAsSuperAdmin, failures);
            assertUnauthenticated(row, failures);
        }

        assertDenied(Row.permission("GET", "/v1/panel/does-not-exist", "admin.settings.view"),
            "unmapped-panel-route", staffWith(Row.permission("GET", "/v1/panel/does-not-exist", "admin.settings.view")), failures);

        if (!failures.isEmpty()) {
            Assertions.fail("Authorization matrix mismatches:\n" + String.join("\n", failures));
        }
    }

    @Test
    void ticketHandlersReadButCannotModifyTicketWorkflowSettings() {
        Consumer<Mocks> ticketHandler = mocks -> {
            authenticateStaff(mocks);
            PermissionService permissionService = mocks.permissionService();
            lenient().when(permissionService.hasAnyPermissionWithPrefix(mocks.server(), ROLE, "ticket.")).thenReturn(true);
            for (String permission : List.of("ticket.view.all", "ticket.reply.all", "ticket.close.all", "appeal.modify")) {
                lenient().when(permissionService.hasPermission(mocks.server(), ROLE, permission)).thenReturn(true);
            }
        };

        for (String path : List.of("/v1/panel/settings/quick-responses", "/v1/panel/settings/ticket-labels")) {
            Assertions.assertTrue(invoke("GET", path, ticketHandler).granted(), () -> "ticket handler denied reading " + path);
            Outcome modify = invoke("PATCH", path, ticketHandler);
            Assertions.assertFalse(modify.granted(), () -> "ticket handler granted modifying " + path);
            Assertions.assertEquals(403, modify.status(), () -> "status for ticket handler modifying " + path);
        }
        for (String path : List.of("/v1/panel/settings/general", "/v1/panel/settings/ticket-forms")) {
            Assertions.assertFalse(invoke("GET", path, ticketHandler).granted(), () -> "ticket handler granted reading " + path);
        }
    }

    @Test
    void everyAnonymousPanelAuthEndpointMapsToARealHandler() {
        RequestMappingHandlerMapping authMappings = PanelHandlerMappingTestSupport.handlerMappingOf(
            mock(PanelAuthController.class), mock(WebAuthnController.class), mock(PasswordAuthController.class),
            mock(SecondFactorSignInController.class));
        Set<String> patterns = authMappings.getHandlerMethods().keySet().stream()
            .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
            .collect(Collectors.toSet());

        for (String endpoint : RESTMappingV1.PANEL_AUTH_ANONYMOUS_ENDPOINTS) {
            String prefix = endpoint.endsWith("/**") ? endpoint.substring(0, endpoint.length() - 3) + "/" : null;
            boolean mapped = prefix == null
                ? patterns.contains(endpoint)
                : patterns.stream().anyMatch(pattern -> pattern.startsWith(prefix));
            Assertions.assertTrue(mapped, () -> endpoint + " is permitted anonymously but no handler serves it");
        }
    }

    @Test
    void unauthenticatedDeniedResponseIsWireFrozen() {
        Outcome outcome = invoke("GET", "/v1/panel/tickets", signedOut -> { });
        Assertions.assertFalse(outcome.granted());
        Assertions.assertEquals(401, outcome.status());
        Assertions.assertEquals(UNAUTHENTICATED_BODY, outcome.body());
        Assertions.assertEquals("application/json", outcome.contentType());
    }

    @Test
    void encodedPanelPrefixesStillRequireSuperAdmin() {
        for (String path : ENCODED_SUPER_ADMIN_PATHS) {
            Outcome staffWithEveryPermission = invoke("GET", path, staffWith(Row.superAdminOnly("GET", path)));
            Assertions.assertFalse(staffWithEveryPermission.granted(), () -> "staff granted on " + path);
            Assertions.assertEquals(403, staffWithEveryPermission.status(), () -> "status for staff on " + path);

            Outcome anonymous = invoke("GET", path, signedOut -> { });
            Assertions.assertFalse(anonymous.granted(), () -> "anonymous granted on " + path);
            Assertions.assertEquals(401, anonymous.status(), () -> "status for anonymous on " + path);

            Assertions.assertTrue(invoke("GET", path, this::signInAsSuperAdmin).granted(), () -> "super admin denied on " + path);
        }
    }

    @Test
    void encodedPanelPrefixesStillEnforceRolePermissions() {
        Outcome outcome = invoke("PATCH", "/%761/%70anel/settings/ticket-forms", staffWithout());
        Assertions.assertFalse(outcome.granted());
        Assertions.assertEquals(403, outcome.status());
        Assertions.assertEquals(DENY_BODY, outcome.body());
        Row permitted = Row.permission("PATCH", "/%761/%70anel/settings/ticket-forms", "admin.settings.modify");
        Assertions.assertTrue(invoke(permitted.method(), permitted.path(), staffWith(permitted)).granted());
    }

    @Test
    void insufficientPermissionDeniedResponseIsWireFrozen() {
        Outcome outcome = invoke("PATCH", "/v1/panel/settings/ticket-forms", staffWithout());
        Assertions.assertFalse(outcome.granted());
        Assertions.assertEquals(403, outcome.status());
        Assertions.assertEquals(DENY_BODY, outcome.body());
        Assertions.assertEquals("application/json", outcome.contentType());
    }

    @Test
    void wrongMethodOnMappedPathLetsPermittedStaffReachDispatcher() {
        Outcome outcome = invoke("DELETE", "/v1/panel/settings/ticket-forms", mocks -> {
            authenticateStaff(mocks);
            lenient().when(mocks.permissionService().hasPermission(mocks.server(), ROLE, "admin.settings.modify")).thenReturn(true);
        });
        Assertions.assertTrue(outcome.granted());
    }

    @Test
    void wrongMethodOnMappedPathDeniesNonPermittedStaffWithFrozenBody() {
        Outcome outcome = invoke("DELETE", "/v1/panel/settings/ticket-forms", staffWithout());
        Assertions.assertFalse(outcome.granted());
        Assertions.assertEquals(403, outcome.status());
        Assertions.assertEquals(DENY_BODY, outcome.body());
        Assertions.assertEquals("application/json", outcome.contentType());
    }

    @Test
    void wrongMethodOnMappedPathIsUnaffectedForSuperAdmin() {
        Outcome outcome = invoke("DELETE", "/v1/panel/settings/ticket-forms", this::signInAsSuperAdmin);
        Assertions.assertTrue(outcome.granted());
    }

    @Test
    void wrongMethodOnPermitAllSiblingPathReachesDispatcherForAnyAuthenticatedStaff() {
        Outcome outcome = invoke("PATCH", "/v1/panel/account/minecraft-link", staffWithout());
        Assertions.assertTrue(outcome.granted());
    }

    private void assertGranted(Row row, String scenario, Consumer<Mocks> setup, List<String> failures) {
        Outcome outcome = invoke(row.method(), row.path(), setup);
        if (!outcome.granted()) {
            failures.add(describe(row, scenario, "expected GRANT but was DENY(" + outcome.status() + ")"));
        }
    }

    private void assertDenied(Row row, String scenario, Consumer<Mocks> setup, List<String> failures) {
        Outcome outcome = invoke(row.method(), row.path(), setup);
        if (outcome.granted()) {
            failures.add(describe(row, scenario, "expected DENY but was GRANT"));
        } else if (outcome.status() != 403) {
            failures.add(describe(row, scenario, "expected 403 but was " + outcome.status()));
        }
    }

    private void assertUnauthenticated(Row row, List<String> failures) {
        Outcome outcome = invoke(row.method(), row.path(), signedOut -> { });
        if (outcome.granted()) {
            failures.add(describe(row, "unauthenticated", "expected DENY but was GRANT"));
        } else if (outcome.status() != 401) {
            failures.add(describe(row, "unauthenticated", "expected 401 but was " + outcome.status()));
        }
    }

    private String describe(Row row, String scenario, String detail) {
        return row.method() + " " + row.path() + " [" + scenario + "] " + detail;
    }

    private Consumer<Mocks> staffWith(Row row) {
        return mocks -> {
            authenticateStaff(mocks);
            grantPermissions(mocks, row);
        };
    }

    @Test
    void eachPunishmentPermissionOpensOnlyItsOwnPlayerWriteRoute() {
        String player = "/v1/panel/players/uuid1";
        String punishment = player + "/punishments/p1";
        List<String> fullOnly = List.of("/v1/panel/players", player + "/usernames", player + "/ips",
            player + "/find-linked");
        Map<String, List<String>> opened = Map.of(
            "punishment.modify.pardon", List.of(punishment + "/modifications"),
            "punishment.modify.duration", List.of(punishment + "/modifications"),
            "punishment.modify.options", List.of(punishment + "/modifications"),
            "punishment.modify.note", List.of(punishment + "/notes"),
            "punishment.modify.evidence", List.of(punishment + "/evidence"),
            "punishment.modify.tickets", List.of(punishment + "/tickets"),
            "punishment.view", List.of(),
            "player.note", List.of(player + "/notes"),
            "player.warn", List.of(),
            "punishment.apply.manual-ban", List.of());
        List<String> routes = new ArrayList<>(fullOnly);
        routes.addAll(List.of(player + "/notes", punishment + "/modifications", punishment + "/notes",
            punishment + "/evidence", punishment + "/tickets"));

        opened.forEach((held, allowed) -> routes.forEach(route -> Assertions.assertEquals(
            allowed.contains(route), invoke("POST", route, staffHolding(held)).granted(), held + " on " + route)));
        routes.forEach(route -> Assertions.assertEquals(!route.equals(player + "/notes"),
            invoke("POST", route, staffHolding("punishment.modify")).granted(), "punishment.modify on " + route));
    }

    private Consumer<Mocks> staffHolding(String held) {
        return mocks -> {
            authenticateStaff(mocks);
            lenient().when(mocks.permissionService().hasPermission(eq(mocks.server()), eq(ROLE), anyString()))
                .thenAnswer(call -> RoleAuthorization.permissionImplies(held, call.getArgument(2)));
            lenient().when(mocks.permissionService().hasAnyPermissionWithPrefix(eq(mocks.server()), eq(ROLE), anyString()))
                .thenAnswer(call -> held.startsWith(call.getArgument(2)));
        };
    }

    private Consumer<Mocks> staffWithout() {
        return this::authenticateStaff;
    }

    private void signInAsSuperAdmin(Mocks mocks) {
        setSession(mocks, SUPER_ADMIN_EMAIL);
        when(mocks.permissionService().isSuperAdmin(mocks.server(), SUPER_ADMIN_EMAIL)).thenReturn(true);
    }

    private void authenticateStaff(Mocks mocks) {
        setSession(mocks, STAFF_EMAIL);
        Staff staff = Staff.builder().email(STAFF_EMAIL).roleId(ROLE).build();
        lenient().when(mocks.staffLookupCache().findByEmail(mocks.server(), STAFF_EMAIL)).thenReturn(Optional.of(staff));
    }

    private void grantPermissions(Mocks mocks, Row row) {
        PermissionService permissionService = mocks.permissionService();
        switch (row.kind()) {
            case PERMISSION -> lenient().when(permissionService.hasPermission(mocks.server(), ROLE, row.permission())).thenReturn(true);
            case PERMISSION_PREFIX -> lenient()
                .when(permissionService.hasAnyPermissionWithPrefix(mocks.server(), ROLE, row.permission())).thenReturn(true);
            case PERMIT -> { }
            case PLAYER_READ -> lenient().when(permissionService.hasPermission(mocks.server(), ROLE, "punishment.view")).thenReturn(true);
            case APPEAL_REPLY -> lenient().when(permissionService.hasPermission(mocks.server(), ROLE, "appeal.modify")).thenReturn(true);
            case SUPER_ADMIN -> lenient()
                .when(permissionService.hasPermission(eq(mocks.server()), eq(ROLE), anyString())).thenReturn(true);
        }
    }

    private void setSession(Mocks mocks, String email) {
        AuthSessionData session = new AuthSessionData();
        session.setEmail(email);
        mocks.request().setAttribute(RequestAttribute.SESSION, session);
    }

    private Outcome invoke(String method, String path, Consumer<Mocks> setup) {
        PermissionService permissionService = mock(PermissionService.class);
        StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(RequestAttribute.SERVER, SERVER);
        Mocks mocks = new Mocks(permissionService, staffLookupCache, request, SERVER);
        setup.accept(mocks);

        PanelPermissionFilter filter = newFilter(permissionService, staffLookupCache);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passed = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passed.set(true);

        try {
            filter.doFilter(request, response, chain);
            return new Outcome(passed.get(), response.getStatus(), response.getContentAsString(), response.getContentType());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final PanelAccessPolicyResolver POLICY_RESOLVER = PanelHandlerMappingTestSupport.buildResolver();

    private PanelPermissionFilter newFilter(PermissionService permissionService, StaffLookupCache staffLookupCache) {
        return new PanelPermissionFilter(permissionService, roleAuthorization(permissionService, staffLookupCache), POLICY_RESOLVER);
    }

    private record Mocks(PermissionService permissionService, StaffLookupCache staffLookupCache,
                         MockHttpServletRequest request, Server server) {
    }

    private record Outcome(boolean granted, int status, String body, String contentType) {
    }

    private static RoleAuthorization roleAuthorization(PermissionService permissionService,
                                                      StaffLookupCache staffLookupCache) {
        return new RoleAuthorization(permissionService, mock(StaffRepository.class), staffLookupCache);
    }
}
