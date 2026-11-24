package dev.simplix.cirrus.model;

import java.util.Locale;

public enum CirrusSoundCategory {

    MASTER,
    MUSIC,
    RECORDS,
    WEATHER,
    BLOCKS,
    HOSTILE,
    NEUTRAL,
    PLAYERS,
    AMBIENT,
    VOICE;

    public static CirrusSoundCategory fromName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MASTER;
        }
    }
}
