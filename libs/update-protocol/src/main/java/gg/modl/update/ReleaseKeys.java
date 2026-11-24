package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReleaseKeys {
    public static final String RESOURCE = "/gg/modl/update/release-keys.txt";
    public static final int MAX_KEYS = 2;
    public static final int KEY_ID_LENGTH = 16;

    private static final int MAX_RESOURCE_BYTES = 16 * 1024;
    private static final String COMMENT = "#";

    private final Map<String, byte[]> keysById;

    private ReleaseKeys(Map<String, byte[]> keysById) {
        this.keysById = Collections.unmodifiableMap(keysById);
    }

    public static ReleaseKeys bundled() {
        try (InputStream input = ReleaseKeys.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return new ReleaseKeys(new LinkedHashMap<String, byte[]>());
            }
            return parse(new String(readAtMost(input), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("The bundled release keys could not be read", e);
        }
    }

    public static ReleaseKeys parse(String text) {
        Map<String, byte[]> keys = new LinkedHashMap<String, byte[]>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith(COMMENT)) {
                continue;
            }
            byte[] der = decode(line);
            keys.put(keyId(der), der);
        }
        if (keys.size() > MAX_KEYS) {
            throw new IllegalStateException("The release keys list " + keys.size() + " keys; at most " + MAX_KEYS + " are allowed");
        }
        return new ReleaseKeys(keys);
    }

    public static String keyId(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            return Hex.encode(digest).substring(0, KEY_ID_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public boolean isEmpty() {
        return keysById.isEmpty();
    }

    public boolean knows(String keyId) {
        return keysById.containsKey(keyId);
    }

    public Set<String> keyIds() {
        return keysById.keySet();
    }

    public List<String> sortedKeyIds() {
        List<String> ids = new ArrayList<String>(keysById.keySet());
        Collections.sort(ids);
        return ids;
    }

    byte @Nullable [] publicKey(String keyId) {
        byte[] der = keysById.get(keyId);
        return der == null ? null : der.clone();
    }

    private static byte[] decode(String line) {
        try {
            return Base64.getDecoder().decode(line);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("A release key line is not base64", e);
        }
    }

    private static byte[] readAtMost(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_RESOURCE_BYTES) {
                throw new IOException("The release keys file is larger than " + MAX_RESOURCE_BYTES + " bytes");
            }
        }
        return buffer.toByteArray();
    }
}
