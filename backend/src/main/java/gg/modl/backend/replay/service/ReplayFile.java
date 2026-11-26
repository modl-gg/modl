package gg.modl.backend.replay.service;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StoredObject;
import java.io.InputStream;
import org.springframework.core.io.AbstractResource;

public final class ReplayFile extends AbstractResource {
    private final ObjectStorage storage;
    private final StoredObject object;

    public ReplayFile(ObjectStorage storage, StoredObject object) {
        this.storage = storage;
        this.object = object;
    }

    @Override
    public String getDescription() {
        return "replay " + object.key();
    }

    @Override
    public InputStream getInputStream() {
        return storage.read(object.key());
    }

    @Override
    public long contentLength() {
        return object.size();
    }

    @Override
    public long lastModified() {
        return object.lastModified().toEpochMilli();
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean isReadable() {
        return true;
    }
}
