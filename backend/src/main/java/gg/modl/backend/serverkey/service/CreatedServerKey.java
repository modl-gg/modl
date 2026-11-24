package gg.modl.backend.serverkey.service;

import gg.modl.backend.serverkey.ServerKey;

public record CreatedServerKey(ServerKey key, String secret) {
    @Override
    public String toString() {
        return "CreatedServerKey[key=" + key + "]";
    }
}
