package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import gg.modl.minecraft.core.util.Hex;
import lombok.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Value
class ServerLibrary {
    String fileName;
    String sha256;
    long size;

    boolean isInstalledAt(Path file) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == size && sha256.equals(Hex.encode(ArtifactDownloader.sha256(file)));
    }
}
