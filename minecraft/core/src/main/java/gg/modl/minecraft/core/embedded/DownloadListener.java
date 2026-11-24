package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.TransferListener;

interface DownloadListener {
    TransferListener listenerFor(ServerLibrary file);

    void completed(ServerLibrary file);
}
