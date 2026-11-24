package gg.modl.backend.hostedimport.intake;

import gg.modl.backend.infrastructure.net.PublicAddressResolver;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;

public final class HttpArchiveTransport implements ArchiveTransport, AutoCloseable {
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(10);
    private static final Timeout READ_TIMEOUT = Timeout.ofSeconds(30);
    private static final Timeout POOL_WAIT = Timeout.ofSeconds(10);
    private static final int MAX_CONNECTIONS = 1;

    private final CloseableHttpClient client;

    public HttpArchiveTransport(PublicAddressResolver resolver) {
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(resolver)
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT)
                    .setSocketTimeout(READ_TIMEOUT)
                    .build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(POOL_WAIT)
                .setResponseTimeout(READ_TIMEOUT)
                .setRedirectsEnabled(false)
                .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableContentCompression()
            .disableCookieManagement()
            .disableAuthCaching()
            .build();
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    @Override
    public Response get(URI uri, long fromByte, @Nullable String etag) throws IOException {
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept-Encoding", "identity");
        if (fromByte > 0) {
            request.setHeader("Range", "bytes=" + fromByte + "-");
            if (etag != null) {
                request.setHeader("If-Match", etag);
            }
        }
        ClassicHttpResponse response = client.executeOpen(null, request, null);
        HttpEntity entity = response.getEntity();
        InputStream content = entity == null ? InputStream.nullInputStream() : entity.getContent();
        return new Response(response.getCode(), contentLength(response), header(response, "ETag"),
            new ResponseBody(content, response));
    }

    private static OptionalLong contentLength(ClassicHttpResponse response) {
        return header(response, "Content-Length").flatMap(HttpArchiveTransport::parsedLength)
            .map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    private static Optional<Long> parsedLength(String value) {
        try {
            long length = Long.parseLong(value.trim());
            return length < 0 ? Optional.empty() : Optional.of(length);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> header(ClassicHttpResponse response, String name) {
        return Optional.ofNullable(response.getFirstHeader(name)).map(Header::getValue);
    }

    private static final class ResponseBody extends FilterInputStream {
        private final ClassicHttpResponse response;

        private ResponseBody(InputStream content, ClassicHttpResponse response) {
            super(content);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            response.close();
        }
    }
}
