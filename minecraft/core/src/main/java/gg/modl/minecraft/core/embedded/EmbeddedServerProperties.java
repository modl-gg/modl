package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.AiSettings;
import gg.modl.minecraft.core.connection.DatabaseSettings;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.connection.SmtpSettings;
import gg.modl.minecraft.core.util.StringUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class EmbeddedServerProperties {
    private EmbeddedServerProperties() {
    }

    static Map<String, String> of(EmbeddedSettings settings, EmbeddedServerPaths paths, String internalApiKey,
                                  Map<String, String> forwardedSwitches) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("MODL_PORT", String.valueOf(settings.getPort()));
        putIfPresent(properties, "MODL_BIND_ADDRESS", settings.getBindAddress());
        putIfPresent(properties, "MODL_PUBLIC_URL", settings.getPublicUrl());
        putIfPresent(properties, "MODL_ADMIN_EMAIL", settings.getAdminEmail());
        properties.put("MODL_TRUST_PROXY_HEADERS", String.valueOf(settings.isTrustProxyHeaders()));
        properties.put("MODL_TRUSTED_PROXY", settings.trustsOnlyLoopbackProxies() ? EmbeddedSettings.TRUSTED_PROXY_LOOPBACK
                : EmbeddedSettings.TRUSTED_PROXY_PRIVATE);
        putDatabase(properties, settings.getDatabase());
        putAi(properties, settings.getAi());
        putSmtp(properties, settings.getSmtp());
        properties.put("MODL_DATA_DIR", paths.getDataDirectory().toString());
        properties.put("MODL_LOG_DIR", paths.getLogDirectory().toString());
        properties.put("MODL_SETUP_CODE_FILE", paths.getSetupCodeFile().toString());
        properties.put("MODL_INTERNAL_API_KEY", internalApiKey);
        properties.putAll(forwardedSwitches);
        return Collections.unmodifiableMap(properties);
    }

    private static void putDatabase(Map<String, String> properties, DatabaseSettings database) {
        putIfPresent(properties, "MODL_DATABASE_URL", database.getUrl());
        putIfPresent(properties, "MODL_DATABASE_USERNAME", database.getUsername());
        putIfPresent(properties, "MODL_DATABASE_PASSWORD", database.getPassword());
        putIfPresent(properties, "MODL_SQLITE_NATIVE_DIR", database.getSqliteNativeDir());
    }

    private static void putAi(Map<String, String> properties, AiSettings ai) {
        putIfPresent(properties, "MODL_AI_PROVIDER", ai.getProvider());
        putIfPresent(properties, "MODL_AI_API_KEY", ai.getApiKey());
        putIfPresent(properties, "MODL_AI_MODEL", ai.getModel());
        putIfPresent(properties, "MODL_AI_BASE_URL", ai.getBaseUrl());
    }

    private static void putSmtp(Map<String, String> properties, SmtpSettings smtp) {
        if (StringUtil.isBlank(smtp.getHost())) return;
        properties.put("MODL_SMTP_HOST", smtp.getHost().trim());
        properties.put("MODL_SMTP_PORT", String.valueOf(smtp.getPort()));
        putIfPresent(properties, "MODL_SMTP_USERNAME", smtp.getUsername());
        putIfPresent(properties, "MODL_SMTP_PASSWORD", smtp.getPassword());
        putIfPresent(properties, "MODL_SMTP_FROM", smtp.getFrom());
        properties.put("MODL_SMTP_STARTTLS", String.valueOf(smtp.isStarttls()));
    }

    private static void putIfPresent(Map<String, String> properties, String key, String value) {
        if (!StringUtil.isBlank(value)) properties.put(key, value.trim());
    }
}
