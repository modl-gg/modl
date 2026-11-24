package gg.modl.backend.storage.repository;

import gg.modl.backend.storage.UploadOrigin;
import java.time.Instant;

public record StorageReservation(String key, long bytes, boolean landed, Instant settleAfter, Instant createdAt, UploadOrigin origin) {
}
