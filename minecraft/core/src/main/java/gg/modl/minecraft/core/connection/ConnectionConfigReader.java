package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.util.YamlValues;

import java.util.Map;

final class ConnectionConfigReader {
    static final int INVALID_NUMBER = -1;

    private final Map<?, ?> root;

    private ConnectionConfigReader(Map<?, ?> root) {
        this.root = root;
    }

    static ConnectionConfig read(Map<?, ?> root) {
        return new ConnectionConfigReader(root).toConfig();
    }

    private ConnectionConfig toConfig() {
        return ConnectionConfig.builder()
                .mode(readMode())
                .embedded(readEmbedded())
                .remote(readRemote())
                .build();
    }

    private ConnectionMode readMode() {
        String raw = string(ConnectionYaml.MODE, "");
        ConnectionMode mode = ConnectionMode.fromYaml(raw);
        if (mode == null) {
            throw new ConnectionConfigException(ConnectionConfig.FILE_NAME + ": mode must be one of "
                    + ConnectionMode.allowedValues() + " (found '" + raw + "').");
        }
        return mode;
    }

    private EmbeddedSettings readEmbedded() {
        return EmbeddedSettings.builder()
                .port(number(ConnectionYaml.EMBEDDED_PORT, EmbeddedSettings.DEFAULT_PORT))
                .bindAddress(string(ConnectionYaml.EMBEDDED_BIND_ADDRESS, EmbeddedSettings.DEFAULT_BIND_ADDRESS))
                .publicUrl(string(ConnectionYaml.EMBEDDED_PUBLIC_URL, ""))
                .adminEmail(string(ConnectionYaml.EMBEDDED_ADMIN_EMAIL, ""))
                .trustProxyHeaders(flag(ConnectionYaml.EMBEDDED_TRUST_PROXY_HEADERS, false))
                .trustedProxy(string(ConnectionYaml.EMBEDDED_TRUSTED_PROXY, EmbeddedSettings.TRUSTED_PROXY_PRIVATE))
                .downloadUrl(string(ConnectionYaml.EMBEDDED_DOWNLOAD_URL, ""))
                .database(DatabaseSettings.builder()
                        .url(string(ConnectionYaml.DATABASE_URL, ""))
                        .username(string(ConnectionYaml.DATABASE_USERNAME, ""))
                        .password(string(ConnectionYaml.DATABASE_PASSWORD, ""))
                        .sqliteNativeDir(string(ConnectionYaml.DATABASE_SQLITE_NATIVE_DIR, ""))
                        .build())
                .ai(AiSettings.builder()
                        .provider(string(ConnectionYaml.AI_PROVIDER, ""))
                        .apiKey(string(ConnectionYaml.AI_API_KEY, ""))
                        .model(string(ConnectionYaml.AI_MODEL, ""))
                        .baseUrl(string(ConnectionYaml.AI_BASE_URL, ""))
                        .build())
                .smtp(SmtpSettings.builder()
                        .host(string(ConnectionYaml.SMTP_HOST, ""))
                        .port(number(ConnectionYaml.SMTP_PORT, SmtpSettings.DEFAULT_PORT))
                        .username(string(ConnectionYaml.SMTP_USERNAME, ""))
                        .password(string(ConnectionYaml.SMTP_PASSWORD, ""))
                        .from(string(ConnectionYaml.SMTP_FROM, ""))
                        .starttls(flag(ConnectionYaml.SMTP_STARTTLS, true))
                        .build())
                .tunnel(TunnelSettings.builder()
                        .provider(string(ConnectionYaml.TUNNEL_PROVIDER, ""))
                        .token(string(ConnectionYaml.TUNNEL_TOKEN, ""))
                        .build())
                .build();
    }

    private RemoteSettings readRemote() {
        return RemoteSettings.builder()
                .url(string(ConnectionYaml.REMOTE_URL, RemoteSettings.DEFAULT_URL))
                .apiKey(string(ConnectionYaml.REMOTE_API_KEY, ""))
                .build();
    }

    private String string(String path, String fallback) {
        return YamlValues.coerceString(YamlValues.nested(root, path), fallback).trim();
    }

    private int number(String path, int fallback) {
        Object value = YamlValues.nested(root, path);
        if (value == null) return fallback;
        return YamlValues.toInt(value instanceof String ? ((String) value).trim() : value, INVALID_NUMBER);
    }

    private boolean flag(String path, boolean fallback) {
        return YamlValues.coerceBoolean(YamlValues.nested(root, path), fallback);
    }
}
