package gg.modl.minecraft.core.impl.menus;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

@Getter @Setter
public class ReportData {
    private String reason;
    private @Nullable String chatLog, details;
    private boolean chatReport;
    private boolean replayCapture;
    private boolean attachReplay;

    public ReportData(String reason, boolean chatReport) {
        this.reason = reason;
        this.chatReport = chatReport;
    }
}
