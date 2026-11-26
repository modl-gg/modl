package gg.modl.minecraft.replay.recording;

import java.io.IOException;
import java.io.OutputStream;

interface ComposeTarget {
    OutputStream open() throws IOException;

    void discard() throws IOException;
}
