package gg.modl.minecraft.core.imports.support;

import gg.modl.minecraft.core.imports.source.SourceException;

public final class PlayerLimit {
    public static final int MAX_PLAYERS = 1_000_000;

    private PlayerLimit() {
    }

    public static void require(long players) throws SourceException {
        if (players > MAX_PLAYERS) throw SourceException.tooLarge(players + " players");
    }
}
