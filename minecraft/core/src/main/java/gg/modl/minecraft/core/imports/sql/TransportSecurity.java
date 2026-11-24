package gg.modl.minecraft.core.imports.sql;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public enum TransportSecurity {
    DISABLED("disable"),
    TRUSTED("trust"),
    VERIFIED("verify-full");

    private static final String SSL_MODE = "sslMode";
    private static final String USE_SSL = "usessl";
    private static final String VERIFY_CERTIFICATE = "verifyservercertificate";
    private static final String TRUST_CERTIFICATE = "trustservercertificate";

    private final String mariaDbMode;

    TransportSecurity(String mariaDbMode) {
        this.mariaDbMode = mariaDbMode;
    }

    public static TransportSecurity of(boolean encrypted, boolean verified) {
        if (!encrypted) return DISABLED;
        return verified ? VERIFIED : TRUSTED;
    }

    public Map<String, String> asMariaDbProperty() {
        Map<String, String> property = new LinkedHashMap<>();
        property.put(SSL_MODE, mariaDbMode);
        return property;
    }

    static Map<String, String> forMariaDb(Map<String, String> configured) {
        Map<String, String> translated = new LinkedHashMap<>();
        Map<String, String> legacy = new LinkedHashMap<>();
        configured.forEach((name, value) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (key.equals(USE_SSL) || key.equals(VERIFY_CERTIFICATE) || key.equals(TRUST_CERTIFICATE)) legacy.put(key, value);
            else translated.put(name, value);
        });
        if (hasKey(translated, SSL_MODE) || !legacy.containsKey(USE_SSL)) return translated;
        boolean encrypted = Boolean.parseBoolean(legacy.get(USE_SSL));
        translated.putAll(of(encrypted, verifies(legacy)).asMariaDbProperty());
        return translated;
    }

    private static boolean verifies(Map<String, String> legacy) {
        String verify = legacy.get(VERIFY_CERTIFICATE);
        if (verify != null) return Boolean.parseBoolean(verify);
        String trust = legacy.get(TRUST_CERTIFICATE);
        return trust == null || !Boolean.parseBoolean(trust);
    }

    private static boolean hasKey(Map<String, String> properties, String wanted) {
        for (String name : properties.keySet()) {
            if (name.equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }
}
