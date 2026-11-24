package gg.modl.backend.storage;

public final class OwnershipMarker {
    public static final String FILE_NAME = ".modl-instance";

    private OwnershipMarker() {
    }

    public static String key(String namespace) {
        return namespace + "/" + FILE_NAME;
    }

    public static boolean isMarkerKey(String key) {
        return key.endsWith("/" + FILE_NAME);
    }
}
