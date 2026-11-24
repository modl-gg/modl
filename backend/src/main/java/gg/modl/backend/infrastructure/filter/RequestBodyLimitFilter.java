package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.rest.ClassifiedRoute;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final long MAX_BODY_BYTES = 16L * 1024 * 1024;
    private static final String TOO_LARGE_CODE = "PAYLOAD_TOO_LARGE";
    private static final String TOO_LARGE_MESSAGE = "The request body is larger than " + MAX_BODY_BYTES + " bytes";

    private final ProtobufErrorResponseWriter protobufErrorResponseWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        ClassifiedRoute route = RouteGroups.classify(request);
        return !route.isApi() || route.isStorageObject() || isStreamedUpload(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(request, response);
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            protobufErrorResponseWriter.write(response, HttpStatus.CONTENT_TOO_LARGE.value(), TOO_LARGE_CODE, TOO_LARGE_MESSAGE);
        } else {
            FilterErrorResponse.writeJson(response, HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE_MESSAGE, TOO_LARGE_CODE);
        }
    }

    private static boolean isStreamedUpload(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE) || type.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private BoundedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new BoundedInputStream(super.getInputStream());
        }
    }

    private static final class BoundedInputStream extends ServletInputStream {
        private final ServletInputStream body;
        private long read;

        private BoundedInputStream(ServletInputStream body) {
            this.body = body;
        }

        @Override
        public int read() throws IOException {
            int value = body.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte [] buffer, int offset, int length) throws IOException {
            int count = body.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > MAX_BODY_BYTES) {
                throw new IOException(TOO_LARGE_MESSAGE);
            }
        }

        @Override
        public boolean isFinished() {
            return body.isFinished();
        }

        @Override
        public boolean isReady() {
            return body.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            body.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }
}
