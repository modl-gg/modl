package gg.modl.backend.tunnel.cloudflared;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.LongConsumer;

@FunctionalInterface
public interface BinaryDownload {
    String download(URI source, Path target, long size, LongConsumer progress) throws IOException;
}
