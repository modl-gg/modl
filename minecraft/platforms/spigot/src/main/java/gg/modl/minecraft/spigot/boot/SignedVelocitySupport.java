package gg.modl.minecraft.spigot.boot;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

final class SignedVelocitySupport {
    static final List<String> REQUIRED_SERVER_CLASSES = Collections.unmodifiableList(Arrays.asList(
            "io.papermc.paper.event.player.AsyncChatEvent",
            "io.papermc.paper.event.player.AsyncChatDecorateEvent",
            "net.minecraft.network.protocol.game.ServerboundChatCommandPacket"
    ));

    private final int requiredJavaVersion;

    SignedVelocitySupport(int requiredJavaVersion) {
        this.requiredJavaVersion = requiredJavaVersion;
    }

    boolean isSupportedBy(int runtimeJavaVersion, Predicate<String> serverHasClass) {
        return runtimeJavaVersion >= requiredJavaVersion && REQUIRED_SERVER_CLASSES.stream().allMatch(serverHasClass);
    }
}
