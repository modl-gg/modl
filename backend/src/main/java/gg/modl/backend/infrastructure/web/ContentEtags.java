package gg.modl.backend.infrastructure.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.core.io.Resource;

final class ContentEtags implements Function<Resource, String> {
    private static final int TAG_HEX_LENGTH = 32;

    private final Map<String, String> byResource = new ConcurrentHashMap<>();

    @Override
    public String apply(Resource resource) {
        String key;
        try {
            key = resource.getURL().toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String known = byResource.get(key);
        if (known != null) {
            return known;
        }
        String computed = digest(resource);
        String raced = byResource.putIfAbsent(key, computed);
        return raced == null ? computed : raced;
    }

    private static String digest(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return "\"" + HexFormat.of().formatHex(digest.digest()).substring(0, TAG_HEX_LENGTH) + "\"";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
