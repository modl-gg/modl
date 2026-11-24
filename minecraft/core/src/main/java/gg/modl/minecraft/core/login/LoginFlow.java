package gg.modl.minecraft.core.login;

import gg.modl.minecraft.api.PlayerNames;
import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.api.http.response.PlayerLoginResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.cache.LoginCache;
import gg.modl.minecraft.core.util.Java8Collections;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class LoginFlow {
    private final HttpClientHolder httpClientHolder;
    private final LoginCache loginCache;
    private final LoginService loginService;
    private final LoginSkins loginSkins;
    private final ForwardedAddressNotice forwardedAddressNotice;
    private final InvalidNameNotice invalidNameNotice;
    private final @Nullable String serverInstanceId;
    private final long timeoutSeconds;

    public LoginFlow(HttpClientHolder httpClientHolder, LoginCache loginCache, LoginService loginService,
                     LoginSkins loginSkins, ForwardedAddressNotice forwardedAddressNotice,
                     InvalidNameNotice invalidNameNotice, @Nullable String serverInstanceId, long timeoutSeconds) {
        this.httpClientHolder = httpClientHolder;
        this.loginCache = loginCache;
        this.loginService = loginService;
        this.loginSkins = loginSkins;
        this.forwardedAddressNotice = forwardedAddressNotice;
        this.invalidNameNotice = invalidNameNotice;
        this.serverInstanceId = serverInstanceId;
        this.timeoutSeconds = timeoutSeconds;
    }

    public long timeoutSeconds() {
        return timeoutSeconds;
    }

    public CompletableFuture<@Nullable String> textureAtJoin(UUID uuid, @Nullable String nativeTexture) {
        return loginSkins.textureAtJoin(uuid, nativeTexture);
    }

    public CompletableFuture<LoginService.LoginResult> begin(LoginAttempt attempt) {
        if (!PlayerNames.isValid(attempt.getUsername())) {
            invalidNameNotice.refused(attempt.getUuid());
            return CompletableFuture.completedFuture(loginService.refuseInvalidName());
        }
        try {
            return exchange(attempt);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(loginService.handleLoginError(asException(failure)));
        }
    }

    private CompletableFuture<LoginService.LoginResult> exchange(LoginAttempt attempt) {
        forwardedAddressNotice.observe(attempt.getIpAddress());
        PlayerLoginRequest request = new PlayerLoginRequest(attempt.getUuid().toString(), attempt.getUsername(),
                attempt.getIpAddress(), loginSkins.skinHashAtLogin(attempt.getUuid(), attempt.getNativeTexture()),
                attempt.getServerName(), serverInstanceId);
        CompletableFuture<LoginExchange> exchanged = httpClientHolder.getClient().playerLogin(request)
                .thenApply(response -> new LoginExchange(request, response));
        return Java8Collections.orTimeout(exchanged, timeoutSeconds, TimeUnit.SECONDS)
                .handle((completed, failure) -> failure == null
                        ? settle(completed, attempt.getUuid())
                        : loginService.handleLoginError(asException(failure)));
    }

    private LoginService.LoginResult settle(LoginExchange exchange, UUID uuid) {
        try {
            loginCache.cacheLoginResult(uuid, exchange.getResponse(), exchange.getRequest().getSkinHash());
            return loginService.processLoginResponse(exchange.getResponse(), uuid);
        } catch (Throwable failure) {
            return loginService.handleLoginError(asException(failure));
        }
    }

    private static Exception asException(Throwable failure) {
        Throwable cause = (failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null ? failure.getCause() : failure;
        return cause instanceof Exception ? (Exception) cause : new ExecutionException(cause);
    }

    @Value
    private static class LoginExchange {
        PlayerLoginRequest request;
        PlayerLoginResponse response;
    }
}
