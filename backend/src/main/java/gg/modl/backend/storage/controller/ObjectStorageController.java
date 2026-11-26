package gg.modl.backend.storage.controller;

import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.storage.StorageFolders;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.local.LocalObjectFile;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.SignedUpload;
import gg.modl.backend.storage.local.SignedUploadOutcome;
import gg.modl.backend.storage.local.UploadGrant;
import gg.modl.backend.storage.service.SignedUploadReceiver;
import gg.modl.backend.storage.service.StorageKeyUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.STORAGE_OBJECTS)
@RequiredArgsConstructor
public class ObjectStorageController {
    private static final String SANDBOX_POLICY = "sandbox; default-src 'none'";
    private static final String NO_SNIFF = "nosniff";
    private static final CacheControl CACHE_CONTROL = CacheControl.maxAge(Duration.ofHours(1)).cachePrivate();
    private static final Set<String> INLINE_MEDIA_TYPES = Set.of(
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/apng",
        "video/mp4", "video/webm", "video/quicktime", "video/x-matroska"
    );

    private final LocalObjectStorage storage;
    private final SignedUploadReceiver signedUploadReceiver;

    @PutMapping("/{*key}")
    public ResponseEntity<Void> upload(
        @PathVariable String key,
        @RequestParam("exp") long expiresAt,
        @RequestParam("len") long contentLength,
        @RequestParam("ct") String contentType,
        @RequestParam("sig") String signature,
        HttpServletRequest request
    ) throws IOException {
        UploadGrant grant = new UploadGrant(StorageKeyUtils.stripLeadingSlash(key), expiresAt, contentLength, contentType);
        SignedUpload upload = new SignedUpload(grant, signature, request.getContentType(), request.getContentLengthLong(),
            request.getInputStream());
        return ResponseEntity.status(statusOf(signedUploadReceiver.receive(upload))).build();
    }

    @GetMapping("/{*key}")
    public ResponseEntity<Resource> download(@PathVariable String key) {
        String objectKey = StorageKeyUtils.stripLeadingSlash(key);
        if (!StorageFolders.servedByStorageUrl(objectKey)) {
            return ResponseEntity.notFound().build();
        }
        return storage.open(objectKey)
            .map(ObjectStorageController::serve)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<Resource> serve(LocalObjectFile file) {
        StoredObject object = file.metadata();
        MediaType mediaType = mediaTypeOf(object.contentType());
        boolean inline = INLINE_MEDIA_TYPES.contains(mediaType.getType() + "/" + mediaType.getSubtype());
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(StorageKeyUtils.extractFileName(object.key()), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CACHE_CONTROL)
            .lastModified(object.lastModified())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("Content-Security-Policy", SANDBOX_POLICY)
            .header("X-Content-Type-Options", NO_SNIFF)
            .body(new FileSystemResource(file.path()));
    }

    private static MediaType mediaTypeOf(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static HttpStatus statusOf(SignedUploadOutcome outcome) {
        return switch (outcome) {
            case STORED -> HttpStatus.OK;
            case INVALID_SIGNATURE -> HttpStatus.FORBIDDEN;
            case CONTENT_TYPE_MISMATCH -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case LENGTH_REQUIRED -> HttpStatus.LENGTH_REQUIRED;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case LENGTH_MISMATCH -> HttpStatus.BAD_REQUEST;
            case ALREADY_EXISTS -> HttpStatus.CONFLICT;
        };
    }
}
