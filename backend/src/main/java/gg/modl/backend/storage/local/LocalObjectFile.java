package gg.modl.backend.storage.local;

import gg.modl.backend.storage.StoredObject;
import java.nio.file.Path;

public record LocalObjectFile(Path path, StoredObject metadata) {
}
