package gg.modl.backend.storage.local;

import java.io.InputStream;

public record SignedUpload(UploadGrant grant, String signature, String requestContentType, long contentLength, InputStream body) {
}
