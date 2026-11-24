package gg.modl.minecraft.core.imports.source;

import lombok.Getter;

@Getter
public final class UnreadablePlayerException extends Exception {
    private final String player;

    public UnreadablePlayerException(String player, Throwable cause) {
        super(cause.toString(), cause);
        this.player = player;
    }
}
