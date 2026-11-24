package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.ConnectionYaml;
import gg.modl.minecraft.core.connection.HttpUrl;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.update.ReleaseVersion;

import java.util.Map;

final class ReleaseSource {
    static final String DEFAULT_BASE_URL = PluginInfo.URL + "/releases";

    private final String baseUrl;

    private ReleaseSource(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    static ReleaseSource of(Map<String, String> forwardedSwitches) {
        String configured = forwardedSwitches.get(ForwardedSwitches.BASE_URL);
        String baseUrl = configured == null ? DEFAULT_BASE_URL : configured;
        try {
            HttpUrl.parseBase(baseUrl);
        } catch (IllegalArgumentException invalid) {
            throw new EmbeddedServerException("The modl release address '" + baseUrl + "' " + invalid.getMessage() + ". Fix "
                    + ConnectionYaml.EMBEDDED_DOWNLOAD_URL + " or " + ForwardedSwitches.BASE_URL + ".");
        }
        return new ReleaseSource(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
    }

    String fileUrl(ReleaseVersion version, String file) {
        return baseUrl + "/download/v" + version + "/" + file;
    }
}
