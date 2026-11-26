package gg.modl.backend.replay.controller;

import gg.modl.backend.replay.service.ReplayFile;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class ReplayFileResponses {
    private static final String FILE_NAME = "replay.modlreplay";
    private static final String NO_SNIFF = "nosniff";

    private ReplayFileResponses() {
    }

    static ResponseEntity<Resource> serve(ReplayFile file) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(FILE_NAME, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", NO_SNIFF)
            .body(file);
    }
}
