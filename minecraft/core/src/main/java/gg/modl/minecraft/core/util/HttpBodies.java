package gg.modl.minecraft.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

public final class HttpBodies {
    private static final int FIRST_ERROR_STATUS = 400;

    private HttpBodies() {
    }

    public static String read(InputStream stream) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    public static String readQuietly(HttpURLConnection connection, int status) {
        if (status >= FIRST_ERROR_STATUS) return readErrorQuietly(connection);
        try {
            InputStream stream = connection.getInputStream();
            return stream == null ? "" : read(stream);
        } catch (IOException unreadable) {
            return "";
        }
    }

    public static String readErrorQuietly(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream();
            return stream == null ? "" : read(stream);
        } catch (IOException unreadable) {
            return "";
        }
    }
}
