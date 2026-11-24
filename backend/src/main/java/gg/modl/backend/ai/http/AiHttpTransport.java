package gg.modl.backend.ai.http;

import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AiHttpTransport implements DisposableBean {
    public static final int ERROR_BODY_LIMIT = 64 * 1024;

    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout POOL_WAIT = Timeout.ofSeconds(5);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(60);
    private static final TimeValue CONNECTION_LIFETIME = TimeValue.ofMinutes(5);
    private static final int MAX_CONNECTIONS = 20;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 10;
    private static final int READ_CHUNK = 8192;

    private final CloseableHttpClient httpsClient;
    private final CloseableHttpClient plainHttpClient;

    @Autowired
    public AiHttpTransport(AiAddressPolicy addressPolicy) {
        this(addressPolicy, AiDnsResolver.HostLookup.SYSTEM);
    }

    AiHttpTransport(AiAddressPolicy addressPolicy, AiDnsResolver.HostLookup lookup) {
        this.httpsClient = client(new AiDnsResolver(addressPolicy, lookup, false));
        this.plainHttpClient = client(new AiDnsResolver(addressPolicy, lookup, true));
    }

    public AiHttpResponse exchange(AiHttpRequest request, AiDeadline deadline) throws AiTransportException {
        if (deadline.expired()) {
            throw new AiTransportException(AiFailureKind.TIMEOUT);
        }
        HttpUriRequestBase message = new HttpUriRequestBase(request.method(), request.uri());
        request.headers().forEach(message::setHeader);
        if (request.jsonBody() != null) {
            message.setEntity(new StringEntity(request.jsonBody(), ContentType.APPLICATION_JSON));
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().name("modl-ai-deadline").start(() -> abortAtDeadline(message, deadline, timedOut));
        try {
            CloseableHttpClient client = "http".equalsIgnoreCase(request.uri().getScheme()) ? plainHttpClient : httpsClient;
            AiHttpResponse exchanged = client.execute(message, response -> read(message, request, response));
            if (timedOut.get()) {
                throw new AiTransportException(AiFailureKind.TIMEOUT);
            }
            return exchanged;
        } catch (RedirectReceived redirect) {
            return new AiHttpResponse(redirect.status, Map.of(), "");
        } catch (ResponseTooLarge tooLarge) {
            throw new AiTransportException(AiFailureKind.RESPONSE_TOO_LARGE);
        } catch (IOException failure) {
            throw new AiTransportException(timedOut.get() ? AiFailureKind.TIMEOUT : classify(failure));
        } finally {
            watchdog.interrupt();
        }
    }

    @Override
    public void destroy() throws IOException {
        try (plainHttpClient) {
            httpsClient.close();
        }
    }

    private static AiHttpResponse read(HttpUriRequestBase message, AiHttpRequest request, ClassicHttpResponse response)
        throws IOException {
        int status = response.getCode();
        Map<String, String> headers = new HashMap<>();
        for (Header header : response.getHeaders()) {
            headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
        }
        if (status >= 300 && status < 400) {
            message.abort();
            throw new RedirectReceived(status);
        }
        int limit = status >= 200 && status < 300 ? request.responseLimit() : ERROR_BODY_LIMIT;
        return new AiHttpResponse(status, headers, bounded(message, response.getEntity(), limit));
    }

    private static String bounded(HttpUriRequestBase message, HttpEntity entity, int limit) throws IOException {
        if (entity == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream content = entity.getContent()) {
            byte[] chunk = new byte[READ_CHUNK];
            int read;
            while ((read = content.read(chunk)) != -1) {
                if (buffer.size() + read > limit) {
                    message.abort();
                    throw new ResponseTooLarge();
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void abortAtDeadline(HttpUriRequestBase message, AiDeadline deadline, AtomicBoolean timedOut) {
        try {
            Thread.sleep(deadline.remaining());
            timedOut.set(true);
            message.abort();
        } catch (InterruptedException finished) {
            Thread.currentThread().interrupt();
        }
    }

    private static AiFailureKind classify(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiBlockedAddressException) {
                return AiFailureKind.BLOCKED_ADDRESS;
            }
            if (cause instanceof SSLException) {
                return AiFailureKind.TLS;
            }
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectTimeoutException) {
                return AiFailureKind.TIMEOUT;
            }
        }
        return failure instanceof InterruptedIOException ? AiFailureKind.TIMEOUT : AiFailureKind.CONNECT;
    }

    private static CloseableHttpClient client(AiDnsResolver resolver) {
        return HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT)
                    .setSocketTimeout(RESPONSE_TIMEOUT)
                    .setTimeToLive(CONNECTION_LIFETIME)
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(POOL_WAIT)
                .setResponseTimeout(RESPONSE_TIMEOUT)
                .setRedirectsEnabled(false)
                .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .build();
    }

    private static final class RedirectReceived extends IOException {
        private final int status;

        RedirectReceived(int status) {
            super("redirect " + status, null);
            this.status = status;
        }
    }

    private static final class ResponseTooLarge extends IOException {
        ResponseTooLarge() {
            super("response too large", null);
        }
    }
}
