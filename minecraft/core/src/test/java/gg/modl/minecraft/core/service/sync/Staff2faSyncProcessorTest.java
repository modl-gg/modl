package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.CachedProfileRegistry;
import gg.modl.minecraft.core.service.Staff2faService;
import gg.modl.minecraft.core.service.Staff2faService.AuthState;
import gg.modl.minecraft.core.support.FakePlatform;
import gg.modl.minecraft.core.support.MapLocaleManager;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Staff2faSyncProcessorTest {
    private static final String CONNECTION_IP = "203.0.113.5";

    private final UUID staff = UUID.randomUUID();
    private final FakePlatform platform = new FakePlatform()
            .register(new AbstractPlayer(staff, "Mod", CONNECTION_IP, true));
    private final CachedProfileRegistry registry = new CachedProfileRegistry();
    private final Cache cache = new Cache(registry);
    private final MapLocaleManager locale = new MapLocaleManager().withFallback(path -> path);
    private final Staff2faService twoFactor = new EnabledTwoFactor(registry);
    private final CachedProfile profile = registry.createProfile(staff);

    Staff2faSyncProcessorTest() {
        profile.setAuthState(AuthState.PENDING);
    }

    @Test
    void anApprovalForTheConnectionsAddressAuthenticatesIt() {
        verify(CONNECTION_IP);

        assertEquals(AuthState.AUTHENTICATED, profile.getAuthState());
    }

    @Test
    void anApprovalForAnotherAddressLeavesTheConnectionUnverified() {
        verify("198.51.100.9");

        assertEquals(AuthState.PENDING, profile.getAuthState());
    }

    @Test
    void anApprovalWithoutAnAddressIsRefused() {
        verify(null);

        assertEquals(AuthState.PENDING, profile.getAuthState());
    }

    @Test
    void aTrustedSessionIsAcceptedOnlyForTheAddressTheBackendChecked() {
        StaffSyncProcessor processor = new StaffSyncProcessor(platform, cache, new RecordingPluginLogger(), locale,
                twoFactor, false);
        SyncResponse.ActiveStaffMember member = new SyncResponse.ActiveStaffMember(staff.toString(), "Mod", "Mod",
                "Moderator", "mod@example.com", "staff-1", Collections.<String>emptyList(), true);

        processor.processActiveStaffMember(member, Collections.singletonMap(staff, "198.51.100.9"));
        assertEquals(AuthState.PENDING, profile.getAuthState());

        processor.processActiveStaffMember(member, Collections.singletonMap(staff, CONNECTION_IP));
        assertEquals(AuthState.AUTHENTICATED, profile.getAuthState());
    }

    private void verify(String approvedIp) {
        new Staff2faSyncProcessor(platform, cache, new RecordingPluginLogger(), locale, twoFactor)
                .processVerifications(Collections.singletonList(
                        new SyncResponse.Staff2faVerification(staff.toString(), approvedIp)));
    }

    private static final class EnabledTwoFactor extends Staff2faService {
        private EnabledTwoFactor(CachedProfileRegistry registry) {
            super(registry, null);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
