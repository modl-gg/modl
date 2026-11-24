package gg.modl.backend.infrastructure.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodyLimitFilterTest {
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(new ProtobufErrorResponseWriter());

    @Test
    void aDeclaredBodyOverTheCapIsRefusedBeforeAnythingReadsIt() throws Exception {
        MockHttpServletRequest request = request("/v1/panel/settings/ticket-forms", "application/json");
        request.setContent(new byte[(int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void aBodyWithoutALengthIsCutOffAtTheCap() throws Exception {
        MockHttpServletRequest request = request("/v3/minecraft/sync", "application/x-protobuf");
        request.setContent(new byte[(int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
        request.removeHeader("Content-Length");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new UnknownLengthRequest(request), new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertThat(passed).isNotNull();
        try (InputStream body = passed.getInputStream()) {
            assertThatThrownBy(body::readAllBytes).isInstanceOf(IOException.class);
        }
    }

    @Test
    void streamedUploadsAndPagesKeepTheirOwnLimits() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
            request("/v1/minecraft/migration/jobs/job-1/file", "application/octet-stream"),
            request("/v1/panel/settings/icon", "multipart/form-data; boundary=x"),
            request("/v1/storage/objects/modl_instance/evidence/P/clip.mp4", "video/mp4")
        }) {
            request.setContent(new byte[(int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(request.getRequestURI()).isSameAs(request);
        }
    }

    private static MockHttpServletRequest request(String path, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType(contentType);
        return request;
    }

    private static final class UnknownLengthRequest extends HttpServletRequestWrapper {
        private UnknownLengthRequest(MockHttpServletRequest request) {
            super(request);
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }
}
