package gg.modl.minecraft.api.http.request;

import lombok.Value;

import java.util.List;

@Value
public class ModifyPunishmentTicketsRequest {
    String punishmentId, issuerName;
    ActionIssuer issuer;
    List<String> addTicketIds, removeTicketIds;
    boolean modifyAssociatedTickets;
}
