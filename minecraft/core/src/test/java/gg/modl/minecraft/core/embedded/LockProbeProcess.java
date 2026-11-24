package gg.modl.minecraft.core.embedded;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class LockProbeProcess {
    static final int HELD = 3;
    static final int FREE = 4;

    private LockProbeProcess() {
    }

    public static void main(String[] args) throws Exception {
        int state;
        try (FileChannel channel = FileChannel.open(Paths.get(args[0]), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            state = lock == null ? HELD : FREE;
        }
        System.exit(state);
    }
}
