package gg.modl.minecraft.replay.recording;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReplayCapture {
    @Nullable CapturedReplay replay;
    @Nullable Refusal refusal;

    public static ReplayCapture written(CapturedReplay replay) {
        return new ReplayCapture(replay, null);
    }

    public static ReplayCapture refused(Refusal refusal) {
        return new ReplayCapture(null, refusal);
    }

    public enum Refusal {
        NOT_RECORDING("the player is not being recorded"),
        STOPPED("the recorder stopped"),
        MEMORY_BUDGET("the replay memory budget has no room for another capture"),
        WRITER_BUSY("too many replays are being written"),
        TOO_LARGE("the replay would exceed the upload limits even for a short window"),
        WRITE_FAILED("the replay file could not be written");

        private final String reason;

        Refusal(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
