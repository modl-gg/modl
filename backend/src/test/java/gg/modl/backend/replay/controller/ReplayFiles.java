package gg.modl.backend.replay.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.replay.service.ReplayFile;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StoredObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

final class ReplayFiles {
    private static final String KEY = "modl_instance/replays/r1.modlreplay";

    private ReplayFiles() {
    }

    static ReplayFile served(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.read(KEY)).thenAnswer(call -> new ByteArrayInputStream(bytes));
        return new ReplayFile(storage, new StoredObject(KEY, bytes.length, StoredObject.DEFAULT_CONTENT_TYPE,
            Instant.parse("2026-11-24T10:00:00Z")));
    }
}
