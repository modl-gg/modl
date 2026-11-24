package gg.modl.backend.hostedimport.intake;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.OptionalLong;

public interface ArchiveTransport {
    Response get(URI uri, long fromByte, @Nullable String etag) throws IOException, InterruptedException;

    record Response(int status, OptionalLong contentLength, Optional<String> etag, InputStream body) {
    }
}
