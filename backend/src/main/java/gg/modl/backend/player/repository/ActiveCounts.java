package gg.modl.backend.player.repository;

public record ActiveCounts(long bans, long mutes) {
    public static final ActiveCounts NONE = new ActiveCounts(0L, 0L);
}
