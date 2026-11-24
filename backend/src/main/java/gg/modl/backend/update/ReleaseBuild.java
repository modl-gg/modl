package gg.modl.backend.update;

import gg.modl.update.ReleaseVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class ReleaseBuild {
    private static final String RESOURCE = "/gg/modl/backend/update/build.properties";
    private static final String VERSION = "version";
    private static final String MIN_UPGRADE_FROM = "minUpgradeFrom";

    private final ReleaseVersion version;
    private final ReleaseVersion minUpgradeFrom;

    private ReleaseBuild(ReleaseVersion version, ReleaseVersion minUpgradeFrom) {
        this.version = version;
        this.minUpgradeFrom = minUpgradeFrom;
    }

    public static ReleaseBuild current() {
        return Holder.CURRENT;
    }

    public static ReleaseBuild of(ReleaseVersion version, ReleaseVersion minUpgradeFrom) {
        return new ReleaseBuild(version, minUpgradeFrom);
    }

    public ReleaseVersion version() {
        return version;
    }

    public String versionText() {
        return version.toString();
    }

    public ReleaseVersion minUpgradeFrom() {
        return minUpgradeFrom;
    }

    private static ReleaseBuild load() {
        Properties properties = new Properties();
        try (InputStream input = ReleaseBuild.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("The server build is incomplete: " + RESOURCE + " is missing");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("The server build information could not be read", e);
        }
        return new ReleaseBuild(ReleaseVersion.parse(properties.getProperty(VERSION)),
            ReleaseVersion.parse(properties.getProperty(MIN_UPGRADE_FROM)));
    }

    private static final class Holder {
        private static final ReleaseBuild CURRENT = load();
    }
}
