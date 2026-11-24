package gg.modl.backend.storage.service;

import org.jspecify.annotations.Nullable;

public final class TempUploadKeys {
    static final String NEW_ENTITY_ID = "new";

    private TempUploadKeys() {
    }

    static String canonicalEntityId(String entityId) {
        return isAnonymousTempEntity(entityId) ? NEW_ENTITY_ID : entityId;
    }

    public static boolean isAnonymousTempEntity(@Nullable String entityId) {
        return entityId != null && NEW_ENTITY_ID.equalsIgnoreCase(entityId.trim());
    }
}
