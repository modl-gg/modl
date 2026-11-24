package gg.modl.backend.storage.repository;

public record PrefixUsage(long files, long bytes) {
    public PrefixUsage plus(PrefixUsage other) {
        return new PrefixUsage(files + other.files, bytes + other.bytes);
    }
}
