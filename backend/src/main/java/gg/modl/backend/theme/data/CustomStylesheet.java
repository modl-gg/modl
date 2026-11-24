package gg.modl.backend.theme.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record CustomStylesheet(String css) {
    public static final CustomStylesheet EMPTY = new CustomStylesheet("");
    private static final String DIGEST = "SHA-256";
    private static final int REVISION_BYTES = 8;

    public CustomStylesheet {
        css = Objects.requireNonNullElse(css, "");
    }

    public boolean empty() {
        return css.isEmpty();
    }

    public byte[] bytes() {
        return css.getBytes(StandardCharsets.UTF_8);
    }

    public String revision() {
        if (empty()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(bytes());
            return HexFormat.of().formatHex(digest, 0, REVISION_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
