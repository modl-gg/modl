package gg.modl.backend.update;

import gg.modl.backend.instance.ProjectLinks;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.update")
public record UpdateProperties(
    @Nullable Boolean check,
    @Nullable Boolean preUpdateBackup,
    @Nullable Boolean allowDowngrade,
    @Nullable String baseUrl,
    @Nullable String apiUrl
) {
    public static final String DEFAULT_BASE_URL = ProjectLinks.RELEASES;
    public static final String DEFAULT_API_URL = ProjectLinks.RELEASES_API;

    public UpdateProperties {
        preUpdateBackup = preUpdateBackup == null || preUpdateBackup;
        allowDowngrade = allowDowngrade != null && allowDowngrade;
        baseUrl = withoutTrailingSlash(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim());
        apiUrl = withoutTrailingSlash(apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : apiUrl.trim());
        requireWebUrl("MODL_UPDATE_BASE_URL", baseUrl);
        requireWebUrl("MODL_UPDATE_API_URL", apiUrl);
    }

    public static UpdateProperties defaults() {
        return new UpdateProperties(null, null, null, null, null);
    }

    public boolean checksForcedOff() {
        return Boolean.FALSE.equals(check);
    }

    public boolean checksManagedByConfig() {
        return check != null;
    }

    public boolean backsUpBeforeUpdating() {
        return Boolean.TRUE.equals(preUpdateBackup);
    }

    public boolean allowsDowngrade() {
        return Boolean.TRUE.equals(allowDowngrade);
    }

    public URI baseUri() {
        return URI.create(baseUrl);
    }

    public URI apiUri() {
        return URI.create(apiUrl);
    }

    public String releasePage(String version) {
        return baseUrl + "/tag/v" + version;
    }

    private static String withoutTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void requireWebUrl(String setting, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(setting + " is not a valid URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null
            || !("https".equals(scheme) || "http".equals(scheme))) {
            throw new IllegalArgumentException(setting + " must be an http or https URL without credentials, query or fragment: "
                + url);
        }
    }
}
