package gg.modl.minecraft.core.packet;

import gg.modl.minecraft.core.util.PluginLogger;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;

import java.util.Collections;
import java.util.List;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PacketInterceptionDecision {
    boolean enabled;
    String reason;
    List<PipelineConflict> conflicts;
    @Getter(AccessLevel.NONE) Notice notice;

    static PacketInterceptionDecision enabled(String reason) {
        return new PacketInterceptionDecision(true, reason, Collections.<PipelineConflict>emptyList(), Notice.DEBUG);
    }

    static PacketInterceptionDecision overridden(String reason, List<PipelineConflict> conflicts) {
        return new PacketInterceptionDecision(true, reason, conflicts, Notice.WARNING);
    }

    static PacketInterceptionDecision disabled(String reason, List<PipelineConflict> conflicts) {
        return new PacketInterceptionDecision(false, reason, conflicts, Notice.WARNING);
    }

    static PacketInterceptionDecision unavailable(String reason) {
        return new PacketInterceptionDecision(false, reason, Collections.<PipelineConflict>emptyList(), Notice.INFO);
    }

    public void report(PluginLogger logger) {
        notice.log(logger, "modl packet interception is " + (enabled ? "on: " : "off: ") + reason);
    }

    private enum Notice {
        DEBUG {
            @Override
            void log(PluginLogger logger, String message) {
                logger.debug(message);
            }
        },
        INFO {
            @Override
            void log(PluginLogger logger, String message) {
                logger.info(message);
            }
        },
        WARNING {
            @Override
            void log(PluginLogger logger, String message) {
                logger.warning(message);
            }
        };

        abstract void log(PluginLogger logger, String message);
    }
}
