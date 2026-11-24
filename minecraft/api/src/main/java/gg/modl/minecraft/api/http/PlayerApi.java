package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.CreatePlayerNoteRequest;
import gg.modl.minecraft.api.http.request.NotificationAcknowledgeRequest;
import gg.modl.minecraft.api.http.request.PlayerDisconnectRequest;
import gg.modl.minecraft.api.http.request.PlayerGetRequest;
import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.api.http.request.PlayerLookupRequest;
import gg.modl.minecraft.api.http.request.PlayerNameRequest;
import gg.modl.minecraft.api.http.response.LinkedAccountsResponse;
import gg.modl.minecraft.api.http.response.OnlinePlayersResponse;
import gg.modl.minecraft.api.http.response.PaginatedNotesResponse;
import gg.modl.minecraft.api.http.response.PlayerGetResponse;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.api.http.response.PlayerLookupResponse;
import gg.modl.minecraft.api.http.response.PlayerNameResponse;
import gg.modl.minecraft.api.http.response.PlayerNoteCreateResponse;
import gg.modl.minecraft.api.http.response.PlayerProfileResponse;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerApi {

    CompletableFuture<PlayerProfileResponse> getPlayerProfile(UUID uuid);

    CompletableFuture<PlayerStandingResponse> getPlayerStanding(UUID uuid);

    CompletableFuture<LinkedAccountsResponse> getLinkedAccounts(UUID uuid);

    CompletableFuture<LinkedAccountsResponse> getLinkedAccounts(UUID uuid, int page, int limit);

    CompletableFuture<PlayerLoginResponse> playerLogin(PlayerLoginRequest request);

    CompletableFuture<Void> playerDisconnect(PlayerDisconnectRequest request);

    CompletableFuture<PlayerGetResponse> getPlayer(PlayerGetRequest request);

    CompletableFuture<PlayerNameResponse> getPlayer(PlayerNameRequest request);

    CompletableFuture<PlayerLookupResponse> lookupPlayer(PlayerLookupRequest request);

    CompletableFuture<PlayerProfileResponse> lookupPlayerProfile(PlayerLookupRequest request);

    CompletableFuture<Void> createPlayerNote(CreatePlayerNoteRequest request);

    CompletableFuture<PlayerNoteCreateResponse> createPlayerNoteWithResponse(CreatePlayerNoteRequest request);

    CompletableFuture<PaginatedNotesResponse> getPlayerNotes(UUID uuid, int page, int limit);

    CompletableFuture<OnlinePlayersResponse> getOnlinePlayers(@Nullable String cursor);

    CompletableFuture<Void> updatePlayerServer(String minecraftUuid, String serverName,
                                                        @Nullable String serverInstanceId);

    CompletableFuture<Void> acknowledgeNotifications(NotificationAcknowledgeRequest request);
}
