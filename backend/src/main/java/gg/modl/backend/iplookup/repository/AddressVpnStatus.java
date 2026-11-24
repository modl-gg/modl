package gg.modl.backend.iplookup.repository;

import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;

import gg.modl.backend.iplookup.model.VpnStatus;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

public final class AddressVpnStatus {
    private AddressVpnStatus() {
    }

    public static Condition lookupOf(Field<String> address) {
        return IP_LOOKUPS.IP.eq(address);
    }

    public static Field<String> of(Field<Boolean> flaggedByEarlierLookup) {
        return DSL.coalesce(IP_LOOKUPS.VPN, DSL.when(flaggedByEarlierLookup.eq(DSL.inline(true)), DSL.inline(VpnStatus.YES.name()))
            .otherwise(DSL.inline(VpnStatus.UNKNOWN.name())));
    }
}
