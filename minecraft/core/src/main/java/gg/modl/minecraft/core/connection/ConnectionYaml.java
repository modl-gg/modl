package gg.modl.minecraft.core.connection;

public final class ConnectionYaml {
    public static final String MODE = "mode";
    public static final String EMBEDDED_PORT = "embedded.port";
    public static final String EMBEDDED_BIND_ADDRESS = "embedded.bind-address";
    public static final String EMBEDDED_PUBLIC_URL = "embedded.public-url";
    public static final String EMBEDDED_ADMIN_EMAIL = "embedded.admin-email";
    public static final String EMBEDDED_TRUST_PROXY_HEADERS = "embedded.trust-proxy-headers";
    public static final String EMBEDDED_TRUSTED_PROXY = "embedded.trusted-proxy";
    public static final String EMBEDDED_DOWNLOAD_URL = "embedded.download-url";
    public static final String DATABASE_URL = "embedded.database.url";
    public static final String DATABASE_USERNAME = "embedded.database.username";
    public static final String DATABASE_PASSWORD = "embedded.database.password";
    public static final String DATABASE_SQLITE_NATIVE_DIR = "embedded.database.sqlite-native-dir";
    public static final String AI_PROVIDER = "embedded.ai.provider";
    public static final String AI_API_KEY = "embedded.ai.api-key";
    public static final String AI_MODEL = "embedded.ai.model";
    public static final String AI_BASE_URL = "embedded.ai.base-url";
    public static final String SMTP_HOST = "embedded.smtp.host";
    public static final String SMTP_PORT = "embedded.smtp.port";
    public static final String SMTP_USERNAME = "embedded.smtp.username";
    public static final String SMTP_PASSWORD = "embedded.smtp.password";
    public static final String SMTP_FROM = "embedded.smtp.from";
    public static final String SMTP_STARTTLS = "embedded.smtp.starttls";
    public static final String TUNNEL_PROVIDER = "embedded.tunnel.provider";
    public static final String TUNNEL_TOKEN = "embedded.tunnel.token";
    public static final String REMOTE_URL = "remote.url";
    public static final String REMOTE_API_KEY = "remote.api-key";

    private ConnectionYaml() {
    }
}
