package com.alessiodp.libby.download;

import java.io.IOException;

public final class LocalFileException extends IOException {
    LocalFileException(String message, IOException cause) {
        super(message, cause);
    }
}
