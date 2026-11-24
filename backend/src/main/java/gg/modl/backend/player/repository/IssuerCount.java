package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import org.jspecify.annotations.Nullable;

public record IssuerCount(PunishmentIssuerKind kind, @Nullable String issuerId, @Nullable String issuerName, long count) {
}
