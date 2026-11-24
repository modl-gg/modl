package gg.modl.backend.player.service;

import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.service.IpLookupListener;
import gg.modl.backend.player.repository.PlayerRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ResolvedAddressApplier implements IpLookupListener {
    private final PlayerRepository playerRepository;
    private final AccountLinkingService accountLinkingService;
    private final Clock clock;

    @Override
    public void resolved(IpLookup lookup) {
        playerRepository.updateIpGeoForAddress(lookup.ip(), LookupGeo.of(lookup.facts()));
        if (lookup.facts().vpn().isKnownClean()) {
            accountLinkingService.linkAddressSharers(lookup.ip(), clock.instant());
        }
    }
}
