package gg.modl.minecraft.api.http;

import gg.modl.minecraft.api.http.request.PanelAccountLinkRequest;
import gg.modl.minecraft.api.http.request.PanelLoginLinkRequest;
import gg.modl.minecraft.api.http.response.PanelAccountLinkResponse;
import gg.modl.minecraft.api.http.response.PanelLoginLinkResponse;

import java.util.concurrent.CompletableFuture;

public interface PanelAccountApi {

    CompletableFuture<PanelAccountLinkResponse> linkPanelAccount(PanelAccountLinkRequest request);

    CompletableFuture<PanelLoginLinkResponse> createPanelLoginLink(PanelLoginLinkRequest request);
}
