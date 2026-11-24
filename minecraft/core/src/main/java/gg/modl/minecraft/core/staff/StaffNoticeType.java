package gg.modl.minecraft.core.staff;

import gg.modl.minecraft.core.StaffAudience;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

public enum StaffNoticeType {
    TICKET_CREATED(StaffAudiences::ticketViewers),
    PUNISHMENT_ISSUED(StaffAudiences::playerViewers),
    PUNISHMENT_PARDONED(StaffAudiences::playerViewers);

    private final Function<StaffAudiences, StaffAudience> audience;

    StaffNoticeType(Function<StaffAudiences, StaffAudience> audience) {
        this.audience = audience;
    }

    public StaffAudience audienceIn(StaffAudiences audiences) {
        return audience.apply(audiences);
    }

    public static @Nullable StaffNoticeType fromWire(String wireType) {
        for (StaffNoticeType type : values()) {
            if (type.name().equals(wireType)) return type;
        }
        return null;
    }
}
