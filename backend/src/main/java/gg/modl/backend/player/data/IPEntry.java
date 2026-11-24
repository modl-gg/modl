package gg.modl.backend.player.data;

import gg.modl.backend.iplookup.model.VpnStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IPEntry {
    @Nullable
    private String ipAddress;

    @Nullable
    private String country;

    @Nullable
    private String region;

    @Nullable
    private String asn;

    private boolean proxy;

    @Builder.Default
    private VpnStatus vpnStatus = VpnStatus.UNKNOWN;

    private boolean hosting;

    private Instant firstLogin;

    @Nullable
    private Instant lastLogin;

    @Builder.Default
    private long loginCount = 1;

    public void recordLogin(Instant login) {
        if (lastLogin == null || login.isAfter(lastLogin)) {
            lastLogin = login;
        }
        if (firstLogin == null || login.isBefore(firstLogin)) {
            firstLogin = login;
        }
        loginCount = Math.max(loginCount, 0) + 1;
    }

    public Instant lastSeen() {
        return lastLogin != null ? lastLogin : firstLogin;
    }
}
