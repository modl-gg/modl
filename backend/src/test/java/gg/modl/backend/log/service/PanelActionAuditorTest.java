package gg.modl.backend.log.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.staff.service.SuperAdminStaffSynthesizer;
import gg.modl.backend.support.TestServers;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PanelActionAuditorTest {
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String STAFF_EMAIL = "mod@example.com";
    private static final String NAMELESS_EMAIL = "nameless@example.com";

    private final Server server = TestServers.server("modl", OWNER_EMAIL);
    private final LogService logService = mock(LogService.class);
    private final StaffLookupCache staffLookupCache = mock(StaffLookupCache.class);
    private final PanelActionAuditor auditor = new PanelActionAuditor(logService, new AuditActors(staffLookupCache));

    @BeforeEach
    void staff() {
        when(staffLookupCache.findByEmail(eq(server), anyString())).thenReturn(Optional.empty());
        when(staffLookupCache.findByEmail(server, STAFF_EMAIL))
            .thenReturn(Optional.of(Staff.builder().email(STAFF_EMAIL).username("Moderator").build()));
        when(staffLookupCache.findByEmail(server, NAMELESS_EMAIL))
            .thenReturn(Optional.of(Staff.builder().email(NAMELESS_EMAIL).username(" ").build()));
    }

    @Test
    void theOwnerWithoutAStaffRecordIsRecordedByTheOwnerNameNotTheirEmail() {
        auditor.recordStaffAction(server, OWNER_EMAIL, "Imported the data of modl.gg export 1");

        verify(logService).recordStaffAction(server, new AuditActor(SuperAdminStaffSynthesizer.SUPER_ADMIN_USERNAME),
            "Imported the data of modl.gg export 1");
    }

    @Test
    void aStaffMemberIsRecordedByTheirUsername() {
        auditor.recordModerationAction(server, STAFF_EMAIL, "Rejected appeal 1");

        verify(logService).recordModerationAction(server, new AuditActor("Moderator"), "Rejected appeal 1");
    }

    @Test
    void anAccountWithoutAUsableNameIsNeverRecordedByEmail() {
        auditor.recordStaffAction(server, NAMELESS_EMAIL, "Changed their password");
        auditor.recordStaffAction(server, "stranger@example.com", "Changed their password");

        verify(logService, times(2))
            .recordStaffAction(server, new AuditActor(AuditActor.UNKNOWN_STAFF), "Changed their password");
    }

    @Test
    void anEmailShapedNameNeverBecomesTheSource() {
        assertEquals(AuditActor.UNKNOWN_STAFF, AuditActor.named("someone@example.com").displayName());
        assertEquals(AuditActor.SYSTEM, AuditActor.named(" ").displayName());
        assertEquals("Console", AuditActor.named("Console").displayName());
        assertEquals("Admin", AuditActor.named("Admin").displayName());
    }
}
