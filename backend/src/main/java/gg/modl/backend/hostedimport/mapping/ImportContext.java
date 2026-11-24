package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.model.IssueLog;
import gg.modl.backend.hostedimport.storage.ImportedUrls;
import java.time.Duration;
import java.time.Instant;

public record ImportContext(
    ArchiveIndex index,
    ImportedIds ids,
    PunishmentIds punishmentIds,
    TicketIds ticketIds,
    ImportedIssuers issuers,
    ImportedTypes types,
    ImportedUrls urls,
    IssueLog issues,
    ImportFit fit,
    Instant exportTime,
    Instant acceptedAt,
    Instant ipCutoff,
    boolean keepsIpHistory,
    Duration notificationLifetime
) {
}
