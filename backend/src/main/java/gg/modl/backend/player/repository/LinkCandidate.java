package gg.modl.backend.player.repository;

public record LinkCandidate(String playerId, boolean sharedCleanAddress) {
    public static LinkCandidate unverified(String playerId) {
        return new LinkCandidate(playerId, false);
    }
}
