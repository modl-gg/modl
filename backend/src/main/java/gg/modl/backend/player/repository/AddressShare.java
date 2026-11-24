package gg.modl.backend.player.repository;

import gg.modl.backend.iplookup.model.VpnStatus;

public record AddressShare(String ip, int accounts, VpnStatus vpn) {
}
