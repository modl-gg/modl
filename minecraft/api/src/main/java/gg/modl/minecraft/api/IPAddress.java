package gg.modl.minecraft.api;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class IPAddress {
    private @Nullable @SerializedName("ipAddress") String ipAddress;
    private @Nullable @SerializedName("country") String country;
    private @Nullable @SerializedName("region") String region;
    private @Nullable @SerializedName("asn") String asn;
    private @Nullable @SerializedName("firstLogin") Instant firstLogin;
    private @SerializedName("logins") List<Instant> logins;
    private @SerializedName("proxy") boolean proxy;
    private @SerializedName("hosting") boolean hosting;
}
