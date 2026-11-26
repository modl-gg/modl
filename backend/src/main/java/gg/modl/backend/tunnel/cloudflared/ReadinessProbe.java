package gg.modl.backend.tunnel.cloudflared;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;

final class ReadinessProbe {
    static final int TIMEOUT_MILLIS = 2_000;
    static final int READY = 200;

    private ReadinessProbe() {
    }

    static boolean ready(URI endpoint) {
        try {
            URLConnection opened = endpoint.toURL().openConnection(Proxy.NO_PROXY);
            if (!(opened instanceof HttpURLConnection connection)) {
                return false;
            }
            try {
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                return connection.getResponseCode() == READY;
            } finally {
                connection.disconnect();
            }
        } catch (IOException unreachable) {
            return false;
        }
    }
}
