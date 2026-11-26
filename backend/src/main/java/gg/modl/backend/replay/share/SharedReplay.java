package gg.modl.backend.replay.share;

import gg.modl.backend.replay.data.ReplayDocument;

public record SharedReplay(ReplayDocument replay, String serverName, String fileUrl) {
}
