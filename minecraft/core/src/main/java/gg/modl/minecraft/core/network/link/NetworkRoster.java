package gg.modl.minecraft.core.network.link;

import gg.modl.minecraft.core.network.NetworkAvailability;
import gg.modl.proto.modl.v1.NetworkMember;
import gg.modl.proto.modl.v1.NetworkRosterEvent;
import gg.modl.proto.modl.v1.ServerRole;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class NetworkRoster implements NetworkAvailability {
    private volatile Set<String> memberNames = Collections.emptySet();

    void replace(NetworkRosterEvent roster) {
        Set<String> names = new LinkedHashSet<>();
        for (NetworkMember member : roster.getMembersList()) {
            if (member.getRole() == ServerRole.SERVER_ROLE_MEMBER && !member.getName().isEmpty()) {
                names.add(member.getName().toLowerCase(Locale.ROOT));
            }
        }
        memberNames = Collections.unmodifiableSet(names);
    }

    void clear() {
        memberNames = Collections.emptySet();
    }

    Set<String> memberNames() {
        return memberNames;
    }

    boolean hasMembers() {
        return !memberNames.isEmpty();
    }

    @Override
    public boolean isEnforcedOn(String serverName) {
        return serverName != null && memberNames.contains(serverName.toLowerCase(Locale.ROOT));
    }
}
