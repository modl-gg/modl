package gg.modl.backend.storage;

public record ReservedBytes(long total, long inFlight) {
    public static final ReservedBytes NONE = new ReservedBytes(0L, 0L);
}
