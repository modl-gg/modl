package gg.modl.minecraft.core.replay;

import java.io.IOException;

public interface ReplayUploadAuthority {
    ReplayUploadGrant requestUpload(ReplayUploadRequest request) throws IOException;

    void confirmUpload(ReplayUploadGrant grant) throws IOException;

    void abandonUpload(ReplayUploadGrant grant);
}
