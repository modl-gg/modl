package gg.modl.backend.backup;

import gg.modl.backend.infrastructure.util.ByteFormatUtil;

public record ArchivedStorage(long files, long bytes) {

    public String describe() {
        return files + " uploaded files (" + ByteFormatUtil.format(bytes) + ")";
    }
}
