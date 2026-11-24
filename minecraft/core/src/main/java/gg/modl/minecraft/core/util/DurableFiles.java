package gg.modl.minecraft.core.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DurableFiles {
    private DurableFiles() {
    }

    public static void replace(Path target, byte[] content) throws IOException {
        Path file = target.toAbsolutePath();
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            output.write(content);
            output.getFD().sync();
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicMoveUnsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
