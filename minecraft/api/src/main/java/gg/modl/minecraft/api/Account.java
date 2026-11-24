package gg.modl.minecraft.api;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter @NoArgsConstructor @AllArgsConstructor
public class Account {
    private @SerializedName("_id") String id;

    private @SerializedName("minecraftUuid") UUID minecraftUuid;

    private @SerializedName("usernames") List<Username> usernames;

    private @SerializedName("notes") List<Note> notes;

    private @SerializedName("ipAddresses") List<IPAddress> ipList;

    private @SerializedName("punishments") List<Punishment> punishments;

    private @SerializedName("pendingNotifications") List<Map<String, Object>> pendingNotifications;

    private @Nullable @SerializedName("data") Map<String, Object> data;

    public List<Username> getUsernames() {
        return usernames != null ? usernames : Collections.emptyList();
    }

    public List<Note> getNotes() {
        return notes != null ? notes : Collections.emptyList();
    }

    public List<IPAddress> getIpList() {
        return ipList != null ? ipList : Collections.emptyList();
    }

    public List<Punishment> getPunishments() {
        return punishments != null ? punishments : Collections.emptyList();
    }

    public Map<String, Object> getData() {
        return data != null ? data : Collections.emptyMap();
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class Username {
        private @SerializedName("username") String username;

        private @Nullable @SerializedName("date") Instant date;
    }
}
