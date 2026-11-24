package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.ClaimTicketRequest;
import gg.modl.minecraft.api.http.request.CreateTicketRequest;
import gg.modl.minecraft.api.http.response.ClaimTicketResponse;
import gg.modl.minecraft.api.http.response.CreateTicketResponse;
import gg.modl.minecraft.api.http.response.TicketsResponse;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface TicketApi {

    CompletableFuture<CreateTicketResponse> createTicket(CreateTicketRequest request);

    CompletableFuture<CreateTicketResponse> createUnfinishedTicket(CreateTicketRequest request);

    CompletableFuture<TicketsResponse> getTickets(@Nullable String status, @Nullable String type);

    CompletableFuture<ClaimTicketResponse> claimTicket(ClaimTicketRequest request);

    CompletableFuture<TicketsResponse> getTicketsByIds(List<String> ticketIds);
}
