package gg.modl.minecraft.api;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

@NoArgsConstructor @AllArgsConstructor @Getter
public final class Evidence {
    private static final String DEFAULT_DISPLAY_TEXT = "Evidence";

    private @Nullable @SerializedName("text") String text;
    private @Nullable @SerializedName("url") String url;
    private @SerializedName("type") String type;
    private @Nullable @SerializedName("uploadedBy") String uploadedBy;
    private @Nullable @SerializedName("uploadedAt") Instant uploadedAt;
    private @Nullable @SerializedName("fileName") String fileName;
    private @Nullable @SerializedName("fileType") String fileType;
    private @Nullable @SerializedName("fileSize") Long fileSize;

    public String getDisplayText() {
        if (url != null && !url.isEmpty()) return url;
        if (text != null && !text.isEmpty()) return text;
        if (fileName != null && !fileName.isEmpty()) return fileName;
        return DEFAULT_DISPLAY_TEXT;
    }
}
