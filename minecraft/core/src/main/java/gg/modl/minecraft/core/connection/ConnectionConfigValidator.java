package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.util.StringUtil;

public final class ConnectionConfigValidator {
    private static final String API_KEY_SOURCE = PanelLocations.MINECRAFT_SERVERS;
    private static final String SERVER_KEY_SOURCE = PanelLocations.ADD_SERVER;

    private ConnectionConfigValidator() {
    }

    public static void validate(ConnectionConfig config, PlatformType platform) {
        ConnectionMode mode = config.getMode();
        if (platform.isProxy() && !mode.runsCore()) {
            throw invalid("mode: " + mode.yamlName() + " is only for Paper servers behind a proxy. Use mode: embedded or mode: remote on a proxy.");
        }
        if (mode == ConnectionMode.EMBEDDED) validateEmbedded(config.getEmbedded());
        if (mode == ConnectionMode.REMOTE) validateRemote(config.getRemote(), RemoteKey.INSTANCE_KEY);
        if (mode == ConnectionMode.BEHIND_PROXY) validateRemote(config.getRemote(), RemoteKey.OPTIONAL_SERVER_KEY);
    }

    private static void validateEmbedded(EmbeddedSettings embedded) {
        requirePort(ConnectionYaml.EMBEDDED_PORT, embedded.getPort());
        requireValue(ConnectionYaml.EMBEDDED_BIND_ADDRESS, embedded.getBindAddress());
        requireHost(ConnectionYaml.EMBEDDED_BIND_ADDRESS, embedded.getBindAddress());
        if (embedded.hasPublicUrl()) requireOrigin(embedded.getPublicUrl());
        requireTrustedProxy(embedded.getTrustedProxy());
        if (!StringUtil.isBlank(embedded.getDownloadUrl())) requireDownloadUrl(embedded.getDownloadUrl());
        if (!StringUtil.isBlank(embedded.getSmtp().getHost())) {
            requireHost(ConnectionYaml.SMTP_HOST, embedded.getSmtp().getHost());
            requirePort(ConnectionYaml.SMTP_PORT, embedded.getSmtp().getPort());
        }
        if (embedded.getTunnel().isEnabled()) requireTunnel(embedded.getTunnel());
    }

    private static void requireTunnel(TunnelSettings tunnel) {
        if (!tunnel.isCloudflare()) {
            throw invalid(ConnectionYaml.TUNNEL_PROVIDER + " must be " + TunnelSettings.CLOUDFLARE + " or empty, not '"
                    + tunnel.getProvider() + "'.");
        }
    }

    private static void validateRemote(RemoteSettings remote, RemoteKey key) {
        try {
            HttpUrl.parseBase(remote.getUrl());
        } catch (IllegalArgumentException e) {
            throw invalid(ConnectionYaml.REMOTE_URL + " '" + remote.getUrl() + "' " + e.getMessage()
                    + ". Set it to the address of your modl server, e.g. http://modl.example.com:8080.");
        }
        if (!remote.hasApiKey()) {
            if (key.optional) return;
            throw invalid(ConnectionYaml.REMOTE_API_KEY + " is empty. Copy " + key.description + " from " + key.source
                    + " into it.");
        }
        requireApiKeyCharacters(ConnectionYaml.REMOTE_API_KEY, remote.getApiKey(), key.source);
    }

    private static void requireTrustedProxy(String trustedProxy) {
        String value = trustedProxy.trim();
        boolean known = EmbeddedSettings.TRUSTED_PROXY_PRIVATE.equalsIgnoreCase(value)
                || EmbeddedSettings.TRUSTED_PROXY_LOOPBACK.equalsIgnoreCase(value);
        if (!known) {
            throw invalid(ConnectionYaml.EMBEDDED_TRUSTED_PROXY + " must be " + EmbeddedSettings.TRUSTED_PROXY_PRIVATE + " or "
                    + EmbeddedSettings.TRUSTED_PROXY_LOOPBACK + ", not '" + trustedProxy + "'.");
        }
    }

    private static void requireDownloadUrl(String downloadUrl) {
        try {
            HttpUrl.parseBase(downloadUrl);
        } catch (IllegalArgumentException e) {
            throw invalid(ConnectionYaml.EMBEDDED_DOWNLOAD_URL + " '" + downloadUrl + "' " + e.getMessage()
                    + ". Set it to the address of a mirror of the modl releases, or leave it empty.");
        }
    }

    private static void requireOrigin(String publicUrl) {
        HttpUrl url;
        try {
            url = HttpUrl.parseBase(publicUrl);
        } catch (IllegalArgumentException e) {
            throw invalid(ConnectionYaml.EMBEDDED_PUBLIC_URL + " '" + publicUrl + "' " + e.getMessage() + ".");
        }
        if (!url.isOrigin()) {
            throw invalid(ConnectionYaml.EMBEDDED_PUBLIC_URL + " must be an origin without a path, e.g. https://panel.example.com.");
        }
    }

    private static void requireHost(String key, String host) {
        try {
            NetworkAddress.normalizeHost(host);
        } catch (IllegalArgumentException e) {
            throw invalid(key + ": " + e.getMessage() + ".");
        }
    }

    private static void requireApiKeyCharacters(String key, String apiKey, String keySource) {
        for (int i = 0; i < apiKey.length(); i++) {
            char character = apiKey.charAt(i);
            if (character <= ' ' || character > '~') {
                throw invalid(key + " contains a character that cannot be part of an API key. Copy the key from "
                        + keySource + " again.");
            }
        }
    }

    private static void requirePort(String key, int port) {
        if (!NetworkAddress.isValidPort(port)) {
            throw invalid(key + " must be a number between " + NetworkAddress.MIN_PORT + " and " + NetworkAddress.MAX_PORT + ".");
        }
    }

    private static void requireValue(String key, String value) {
        if (StringUtil.isBlank(value)) throw invalid(key + " must not be empty.");
    }

    private static ConnectionConfigException invalid(String detail) {
        return new ConnectionConfigException(ConnectionConfig.FILE_NAME + ": " + detail);
    }

    private enum RemoteKey {
        INSTANCE_KEY("the API key", API_KEY_SOURCE, false),
        OPTIONAL_SERVER_KEY("a server key", SERVER_KEY_SOURCE, true);

        private final String description;
        private final String source;
        private final boolean optional;

        RemoteKey(String description, String source, boolean optional) {
            this.description = description;
            this.source = source;
            this.optional = optional;
        }
    }
}
