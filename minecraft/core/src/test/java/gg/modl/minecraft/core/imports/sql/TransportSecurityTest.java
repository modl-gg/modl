package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.core.util.Java8Collections;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransportSecurityTest {
    @Test
    void legacySslSwitchesBecomeTheMariaDbSslMode() {
        assertEquals("disable", TransportSecurity.forMariaDb(Java8Collections.mapOf("useSSL", "false")).get("sslMode"));
        assertEquals("trust", TransportSecurity.forMariaDb(Java8Collections.mapOf("useSSL", "true",
                "verifyServerCertificate", "false")).get("sslMode"));
        assertEquals("verify-full", TransportSecurity.forMariaDb(Java8Collections.mapOf("useSSL", "true")).get("sslMode"));
        assertEquals("trust", TransportSecurity.forMariaDb(Java8Collections.mapOf("useSSL", "true",
                "trustServerCertificate", "true")).get("sslMode"));
    }

    @Test
    void anExplicitSslModeWinsAndOtherPropertiesStay() {
        Map<String, String> translated = TransportSecurity.forMariaDb(Java8Collections.mapOf("sslMode", "verify-ca",
                "useSSL", "false", "characterEncoding", "utf8"));

        assertEquals("verify-ca", translated.get("sslMode"));
        assertEquals("utf8", translated.get("characterEncoding"));
        assertFalse(translated.containsKey("useSSL"));
    }

    @Test
    void theRemoteDatabaseNeverPrintsItsPassword() {
        RemoteDatabase.RemoteDatabaseBuilder builder = RemoteDatabase.builder().driver(JdbcDriver.MARIADB).host("db")
                .port(3306).database("bans").username("modl").password(DatabasePassword.of("s3cret-pass"));

        assertFalse(builder.toString().contains("s3cret-pass"));
        assertFalse(builder.build().toString().contains("s3cret-pass"));
    }
}
