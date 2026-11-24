package gg.modl.minecraft.core.network;

import lombok.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Value
public class ModerationState {
    public static final ModerationState EMPTY =
            new ModerationState(Collections.<UUID, Freeze>emptyMap(), Collections.<UUID, StaffSessionState>emptyMap());

    Map<UUID, Freeze> frozen;
    Map<UUID, StaffSessionState> staff;

    public ModerationState(Map<UUID, Freeze> frozen, Map<UUID, StaffSessionState> staff) {
        this.frozen = Collections.unmodifiableMap(new LinkedHashMap<>(frozen));
        this.staff = Collections.unmodifiableMap(new LinkedHashMap<>(staff));
    }
}
