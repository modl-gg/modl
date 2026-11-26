package gg.modl.backend.replay.share;

import gg.modl.backend.replay.data.ReplayShare;

public record CreatedReplayShare(ReplayShare share, String token, String url) {
    @Override
    public String toString() {
        return "CreatedReplayShare[id=" + share.id() + "]";
    }
}
