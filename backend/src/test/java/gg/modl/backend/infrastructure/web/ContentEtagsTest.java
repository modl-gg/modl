package gg.modl.backend.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

class ContentEtagsTest {
    private static final Instant RELEASED = Instant.parse("2026-11-01T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void aRollbackToAnOlderJarRevalidatesTheIndexItReplaced() throws Exception {
        Path older = installed("older", "<html>3.0.0</html>", RELEASED);
        Path newer = installed("newer", "<html>3.0.1</html>", RELEASED.plusSeconds(86_400));
        MockHttpServletResponse fromNewer = get(newer, null);

        MockHttpServletResponse afterRollback = get(older, fromNewer.getHeader("ETag"));

        assertThat(afterRollback.getStatus()).isEqualTo(200);
        assertThat(afterRollback.getContentAsString()).contains("3.0.0");
        assertThat(afterRollback.getHeader("Last-Modified")).isNull();
    }

    @Test
    void anUnchangedFileIsNotSentAgainEvenFromAnotherJar() throws Exception {
        Path first = installed("first", "<html>same</html>", RELEASED);
        Path second = installed("second", "<html>same</html>", RELEASED.plusSeconds(60));

        assertThat(get(second, get(first, null).getHeader("ETag")).getStatus()).isEqualTo(304);
    }

    private Path installed(String jar, String index, Instant modified) throws Exception {
        Path directory = Files.createDirectory(root.resolve(jar));
        Path file = Files.writeString(directory.resolve("index.html"), index);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return directory;
    }

    private static MockHttpServletResponse get(Path directory, String etag) throws Exception {
        ResourceHttpRequestHandler handler = new ResourceHttpRequestHandler();
        handler.setLocations(List.of(new FileSystemResource(directory + "/")));
        handler.setUseLastModified(false);
        handler.setEtagGenerator(new ContentEtags());
        handler.afterPropertiesSet();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "index.html");
        if (etag != null) {
            request.addHeader("If-None-Match", etag);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handleRequest(request, response);
        return response;
    }
}
