package gg.modl.backend.theme.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.theme.service.CustomCssService;
import gg.modl.backend.theme.service.CustomCssService.PublishedStylesheet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping(RESTMappingV1.PUBLIC_THEME_STYLESHEET)
@RequiredArgsConstructor
public class PublicThemeStylesheetController {
    static final MediaType TEXT_CSS = new MediaType("text", "css", StandardCharsets.UTF_8);
    private static final Duration REVISION_MAX_AGE = Duration.ofDays(365);

    private final CustomCssService stylesheets;

    @GetMapping
    public @Nullable ResponseEntity<byte[]> stylesheet(@RequestParam(name = "v", required = false) @Nullable String requestedRevision,
                                                       WebRequest request) {
        PublishedStylesheet published = stylesheets.published();
        String etag = "\"" + published.revision() + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
            .contentType(TEXT_CSS)
            .eTag(etag)
            .cacheControl(cacheControl(requestedRevision, published.revision()))
            .body(published.stylesheet().bytes());
    }

    private static CacheControl cacheControl(@Nullable String requestedRevision, String currentRevision) {
        return !currentRevision.isEmpty() && currentRevision.equals(requestedRevision)
            ? CacheControl.maxAge(REVISION_MAX_AGE).cachePublic().immutable()
            : CacheControl.noCache();
    }
}
