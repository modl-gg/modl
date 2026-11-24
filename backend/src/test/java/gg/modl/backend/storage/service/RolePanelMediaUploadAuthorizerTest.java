package gg.modl.backend.storage.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RolePanelMediaUploadAuthorizerTest {

    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String STAFF_EMAIL = "staff@example.com";
    private static final String ROLE = "role";
    private static final Server SERVER = TestServers.server("server", ADMIN_EMAIL);

    private final RoleAuthorization roleAuthorization = mock(RoleAuthorization.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final RolePanelMediaUploadAuthorizer authorizer = new RolePanelMediaUploadAuthorizer(roleAuthorization, permissionService);
    private String caller = STAFF_EMAIL;

    @Test
    void ticketRepliersMayAttachTicketFiles() {
        staffHolding(PermissionService.TICKET_REPLY_ALL);

        assertPresignPermitted("ticket");
        assertConfirmPermitted("db/tickets/t1/upload.png");
        assertConfirmPermitted("db/ticket/upload.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.APPEAL_MODIFY, PermissionService.TICKET_REPLY_ALL})
    void appealHandlersMayAttachAppealFiles(String permission) {
        staffHolding(permission);

        assertPresignPermitted("appeal");
        assertConfirmPermitted("db/appeal/a1/upload.png");
    }

    @Test
    void appealModifiersWithoutTicketReplyMayNotAttachTicketFiles() {
        staffHolding(PermissionService.APPEAL_MODIFY);

        assertPresignDenied("ticket");
    }

    @Test
    void evidenceManagersMayAttachEvidence() {
        staffHolding(PermissionService.PUNISHMENT_MODIFY_EVIDENCE);

        assertPresignPermitted("evidence");
        assertConfirmPermitted("db/evidence/upload.png");
    }

    @Test
    void punishmentIssuersMayAttachEvidence() {
        staffHolding();
        when(permissionService.hasAnyPermissionWithPrefix(SERVER, ROLE, PermissionService.PUNISHMENT_APPLY_PREFIX)).thenReturn(true);

        assertPresignPermitted("evidence");
    }

    @Test
    void punishmentViewersMayNotAttachEvidence() {
        staffHolding(PermissionService.PUNISHMENT_VIEW);

        assertPresignDenied("evidence");
    }

    @Test
    void contentModifiersMayUploadArticleMedia() {
        staffHolding(PermissionService.ADMIN_SETTINGS_MODIFY_CONTENT);

        assertPresignPermitted("article");
        assertConfirmPermitted("db/article/upload.png");
        assertPresignDenied("ticket");
    }

    @Test
    void settingsModifiersMayUploadServerIcons() {
        staffHolding(PermissionService.ADMIN_SETTINGS_MODIFY);

        assertPresignPermitted("server-icon");
        assertConfirmPermitted("db/server-icon/upload.png");
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidence", "article", "server-icon", "replay"})
    void ticketHandlersMayNotUploadOtherCategories(String uploadType) {
        staffHolding(PermissionService.TICKET_REPLY_ALL, PermissionService.APPEAL_MODIFY, PermissionService.TICKET_VIEW_ALL);

        assertPresignDenied(uploadType);
    }

    @Test
    void confirmTakesTheCategoryFromTheKeyNotTheClient() {
        staffHolding(PermissionService.TICKET_REPLY_ALL);

        assertConfirmDenied("db/evidence/upload.png");
        assertConfirmDenied("db/article/upload.png");
        assertConfirmDenied("db/replays/upload.bin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidence", "ticket", "appeal", "article", "server-icon"})
    void superAdminMayUploadEveryPanelCategory(String uploadType) {
        superAdmin();

        assertPresignPermitted(uploadType);
    }

    @Test
    void replaysAreNeverUploadedThroughThePanel() {
        superAdmin();

        assertPresignDenied("replay");
        assertConfirmDenied("db/replay/upload.bin");
    }

    @Test
    void unknownUploadTypeIsRejectedAsInvalid() {
        superAdmin();

        assertThrows(ValidationException.class, () -> authorizer.authorizePresign(SERVER, ADMIN_EMAIL, "backup"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonexistent-key", "db/../evidence/upload.png", "db/backup/upload.zip", ""})
    void unrecognizedKeysAreRejected(String key) {
        superAdmin();

        assertConfirmDenied(key);
    }

    @Test
    void unidentifiedCallersAreRejected() {
        when(roleAuthorization.panelPerformer(SERVER, null)).thenReturn(RoleAuthorization.PerformerAuthority.unidentified());

        assertThrows(ForbiddenException.class, () -> authorizer.authorizePresign(SERVER, null, "ticket"));
    }

    private void staffHolding(String... permissions) {
        caller = STAFF_EMAIL;
        when(roleAuthorization.panelPerformer(SERVER, STAFF_EMAIL))
            .thenReturn(new RoleAuthorization.PerformerAuthority(STAFF_EMAIL, ROLE, false, true));
        for (String permission : permissions) {
            when(permissionService.hasPermission(SERVER, ROLE, permission)).thenReturn(true);
        }
    }

    private void superAdmin() {
        caller = ADMIN_EMAIL;
        when(roleAuthorization.panelPerformer(SERVER, ADMIN_EMAIL))
            .thenReturn(new RoleAuthorization.PerformerAuthority(ADMIN_EMAIL, null, true, true));
    }

    private void assertPresignPermitted(String uploadType) {
        assertDoesNotThrow(() -> authorizer.authorizePresign(SERVER, caller, uploadType));
    }

    private void assertPresignDenied(String uploadType) {
        assertThrows(ForbiddenException.class, () -> authorizer.authorizePresign(SERVER, caller, uploadType));
    }

    private void assertConfirmPermitted(String key) {
        assertDoesNotThrow(() -> authorizer.authorizeConfirm(SERVER, caller, key));
    }

    private void assertConfirmDenied(String key) {
        assertThrows(ForbiddenException.class, () -> authorizer.authorizeConfirm(SERVER, caller, key));
    }
}
