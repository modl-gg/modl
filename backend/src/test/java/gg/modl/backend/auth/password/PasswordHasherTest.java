package gg.modl.backend.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Security;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private static final String REFERENCE_HASH =
        "$argon2id$v=19$m=256,t=2,p=1$c29tZXNhbHQ$nf65EOgLrQMR/uIPnA4rEsF5h7TKyQwu9U1bMCHGi/4";

    private final PasswordHashingGate gate = new PasswordHashingGate(1);
    private final PasswordHasher hasher = new PasswordHasher(gate);

    @AfterEach
    void stopGate() throws InterruptedException {
        gate.destroy();
    }

    @Test
    void theReferenceImplementationVectorMatches() {
        assertThat(hasher.matches("password", REFERENCE_HASH)).isTrue();
        assertThat(hasher.matches("Password", REFERENCE_HASH)).isFalse();
    }

    @Test
    void newHashesUseTheConfiguredArgon2idParameters() {
        String hash = hasher.hash("correct horse battery staple");

        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(hash.length()).isLessThanOrEqualTo(255);
        assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
        assertThat(hasher.needsRehash(hash)).isFalse();
    }

    @Test
    void storedHashesOutsideTheAcceptedParametersNeverMatch() {
        assertThat(PasswordHasher.isAcceptable(REFERENCE_HASH)).isTrue();
        assertThat(PasswordHasher.isAcceptable(REFERENCE_HASH.replace("argon2id", "argon2i"))).isFalse();
        assertThat(PasswordHasher.isAcceptable(REFERENCE_HASH.replace("m=256", "m=4"))).isFalse();
        assertThat(PasswordHasher.isAcceptable(REFERENCE_HASH.replace("m=256", "m=99999"))).isFalse();
        assertThat(PasswordHasher.isAcceptable(REFERENCE_HASH.replace("t=2", "t=9"))).isFalse();
        assertThat(PasswordHasher.isAcceptable(REFERENCE_HASH.replace("p=1", "p=4"))).isFalse();
        assertThat(PasswordHasher.isAcceptable("$2a$10$abcdefghijklmnopqrstuv")).isFalse();
        assertThat(hasher.matches("password", REFERENCE_HASH.replace("t=2", "t=9"))).isFalse();
    }

    @Test
    void theUnknownAccountHashIsWellFormedAndNeverMatches() {
        assertThat(PasswordHasher.isAcceptable(PasswordHasher.UNKNOWN_ACCOUNT_HASH)).isTrue();
        assertThat(hasher.matches("", PasswordHasher.UNKNOWN_ACCOUNT_HASH)).isFalse();
        assertThat(hasher.matches("modl-no-account", PasswordHasher.UNKNOWN_ACCOUNT_HASH)).isFalse();
    }

    @Test
    void weakerStoredParametersAskForARehash() {
        assertThat(hasher.needsRehash(REFERENCE_HASH)).isTrue();
    }

    @Test
    void hashingRegistersNoSecurityProvider() {
        hasher.hash("correct horse battery staple");

        assertThat(Security.getProvider("BC")).isNull();
    }
}
