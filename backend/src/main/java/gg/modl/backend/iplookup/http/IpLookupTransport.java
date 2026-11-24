package gg.modl.backend.iplookup.http;

import gg.modl.backend.infrastructure.net.PublicAddressResolver;
import gg.modl.backend.iplookup.provider.IpLookupCall;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IpLookupTransport implements DisposableBean {
    static final Set<String> ALLOWED_HOSTS = Set.of("proxycheck.io", "vpnapi.io", "v2.api.iphub.info", "api.ipapi.is");
    static final int RESPONSE_LIMIT = 32 * 1024;

    private static final String HTTPS = "https";
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(5);
    private static final Timeout POOL_WAIT = Timeout.ofSeconds(5);
    private static final TimeValue CONNECTION_LIFETIME = TimeValue.ofMinutes(5);
    private static final int MAX_CONNECTIONS = 4;
    private static final int READ_CHUNK = 4096;

    private final CloseableHttpClient client;

    @Autowired
    public IpLookupTransport() {
        this(PublicAddressResolver.HostLookup.SYSTEM);
    }

    IpLookupTransport(PublicAddressResolver.HostLookup lookup) {
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicAddressResolver(lookup))
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS)
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

    public IpLookupResponse exchange(IpLookupCall call) throws IOException {
        requireAllowedTarget(call);
        HttpUriRequestBase request = call.jsonBody() == null ? new HttpGet(call.uri()) : new HttpPost(call.uri());
        request.setHeader("Accept", "application/json");
        call.headers().forEach(request::setHeader);
        if (call.jsonBody() != null) {
            request.setEntity(new StringEntity(call.jsonBody(), ContentType.APPLICATION_JSON));
        }
        return client.execute(request, response -> read(request, response));
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }

    static void requireAllowedTarget(IpLookupCall call) {
        boolean https = HTTPS.equalsIgnoreCase(call.uri().getScheme());
        boolean defaultPort = call.uri().getPort() == -1;
        if (!https || !defaultPort || call.uri().getRawUserInfo() != null || !ALLOWED_HOSTS.contains(call.uri().getHost())) {
            throw new IllegalArgumentException("IP lookups go only to the built-in providers over https");
        }
    }

    private static IpLookupResponse read(HttpUriRequestBase request, ClassicHttpResponse response) throws IOException {
        return new IpLookupResponse(response.getCode(), bounded(request, response.getEntity()));
    }

    private static String bounded(HttpUriRequestBase request, HttpEntity entity) throws IOException {
        if (entity == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream content = entity.getContent()) {
            byte[] chunk = new byte[READ_CHUNK];
            int read;
            while ((read = content.read(chunk)) != -1) {
                if (buffer.size() + read > RESPONSE_LIMIT) {
                    request.abort();
                    throw new IOException("IP lookup response is larger than " + RESPONSE_LIMIT + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
