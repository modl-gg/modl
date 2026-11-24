package gg.modl.backend.auth.password;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PasswordHasher {
    static final String UNKNOWN_ACCOUNT_HASH =
        "$argon2id$v=19$m=19456,t=2,p=1$bW9kbC1uby1hY2NvdW50IQ$bW9kbC1uby1hY2NvdW50LWhhc2gtbmV2ZXItbWF0Y2g";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19456;
    private static final int ITERATIONS = 2;
    private static final int MIN_MEMORY_KIB = 8;
    private static final int MAX_MEMORY_KIB = 65536;
    private static final int MAX_ITERATIONS = 5;
    private static final Pattern STORED_FORMAT =
        Pattern.compile("\\$argon2id\\$v=19\\$m=(\\d{1,5}),t=(\\d),p=1\\$[A-Za-z0-9+/]{11,64}\\$[A-Za-z0-9+/]{22,128}");

    private final Argon2PasswordEncoder encoder =
        new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    private final PasswordHashingGate gate;

    public PasswordHasher(PasswordHashingGate gate) {
        this.gate = gate;
    }

    public String hash(String normalizedPassword) {
        return gate.run(() -> encoder.encode(normalizedPassword));
    }

    public boolean matches(String normalizedPassword, String storedHash) {
        if (!isAcceptable(storedHash)) {
            log.error("A stored password hash has an unsupported format; treating it as no match");
            burnForUnknownAccount(normalizedPassword);
            return false;
        }
        return gate.run(() -> encoder.matches(normalizedPassword, storedHash));
    }

    public void burnForUnknownAccount(String normalizedPassword) {
        gate.run(() -> encoder.matches(normalizedPassword, UNKNOWN_ACCOUNT_HASH));
    }

    public boolean needsRehash(String storedHash) {
        return isAcceptable(storedHash) && encoder.upgradeEncoding(storedHash);
    }

    static boolean isAcceptable(String storedHash) {
        if (storedHash == null) {
            return false;
        }
        Matcher format = STORED_FORMAT.matcher(storedHash);
        if (!format.matches()) {
            return false;
        }
        int memory = Integer.parseInt(format.group(1));
        int iterations = Integer.parseInt(format.group(2));
        return memory >= MIN_MEMORY_KIB && memory <= MAX_MEMORY_KIB && iterations >= 1 && iterations <= MAX_ITERATIONS;
    }
}
