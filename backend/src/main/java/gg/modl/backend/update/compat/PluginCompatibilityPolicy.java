package gg.modl.backend.update.compat;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.VersionCompatibility;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PluginCompatibilityPolicy {
    static final int NOTICE_CAPACITY = 64;

    private final ReleaseBuild build;
    private final RefusedServers refusedServers;
    private final OperatorNotice notice;
    private final Set<String> noticed = new LinkedHashSet<>();

    public PluginCompatibilityPolicy(ReleaseBuild build, RefusedServers refusedServers, OperatorNotice notice) {
        this.build = build;
        this.refusedServers = refusedServers;
        this.notice = notice;
    }

    public VersionCompatibility compatibilityOf(String pluginVersion) {
        return VersionCompatibility.between(ReleaseVersion.tryParse(pluginVersion), build.version());
    }

    public void requireCompatible(PluginIdentity identity) {
        VersionCompatibility compatibility = compatibilityOf(identity.pluginVersion());
        if (compatibility.isCompatible()) {
            refusedServers.accepted(identity.bootId());
            return;
        }
        refusedServers.refused(identity, compatibility);
        announceOnce(identity, compatibility);
        throw new VersionMismatchException(compatibility, build.versionText());
    }

    public String serverVersion() {
        return build.versionText();
    }

    private void announceOnce(PluginIdentity identity, VersionCompatibility compatibility) {
        String key = identity.pluginVersion() + "|" + identity.platform();
        synchronized (noticed) {
            if (noticed.contains(key) || noticed.size() >= NOTICE_CAPACITY) {
                return;
            }
            noticed.add(key);
        }
        notice.publish("Refused a {} server running modl {}: this modl server is {}. {}", identity.platform(),
            identity.pluginVersion(), build.versionText(), remedy(identity, compatibility));
    }

    private String remedy(PluginIdentity identity, VersionCompatibility compatibility) {
        ReleaseVersion plugin = ReleaseVersion.tryParse(identity.pluginVersion());
        if (compatibility == VersionCompatibility.SERVER_OLDER && plugin != null) {
            return "Update this modl server to " + plugin.line() + ".x.";
        }
        return "Update the plugin on that server to " + build.version().line() + ".x.";
    }
}
