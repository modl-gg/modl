package gg.modl.minecraft.core.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class UrlEncoding {
    private UrlEncoding() {
    }

    @SuppressWarnings("JdkObsolete")
    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is not supported", impossible);
        }
    }
}
