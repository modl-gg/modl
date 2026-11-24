package gg.modl.backend.player.service;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.player.repository.IpGeo;

final class LookupGeo {
    private LookupGeo() {
    }

    static IpGeo of(IpFacts facts) {
        return new IpGeo(facts.country(), facts.region(), facts.asn(), facts.vpn() == VpnStatus.YES, facts.hosting());
    }
}
