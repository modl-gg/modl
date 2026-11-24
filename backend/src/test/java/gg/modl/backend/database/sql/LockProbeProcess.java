package gg.modl.backend.database.sql;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LockProbeProcess {
    static final String FREE = "lock free";
    static final String HELD = "lock held";

    private LockProbeProcess() {
    }

    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            System.out.println(lock == null ? HELD : FREE);
        }
    }
}
