package gg.modl.minecraft.core;

import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.impl.http.ModlHttpClientV2Impl;
import gg.modl.minecraft.core.impl.http.ModlHttpClientV3Impl;
import gg.modl.proto.modl.v1.ApiError;
import lombok.Getter;

import java.util.logging.Logger;

@Getter
public class HttpManager {
    private static final Logger logger = Logger.getLogger(HttpManager.class.getName());

    private static final String PROTO_V3_PROPERTY = "modl.http.protoV3.enabled";
    private static final String PROTO_V3_ENV = "MODL_HTTP_PROTO_V3_ENABLED";

    private final ModlHttpClient httpClient;
    private final BackendConnections connections;
    private final String apiUrl;
    private final boolean debugHttp;

    public HttpManager(BackendConnections connections, boolean debugHttp) {
        this.connections = connections;
        this.debugHttp = debugHttp;

        boolean protoV3Enabled = isProtoV3Enabled() && isProtoRuntimeCompatible();
        if (protoV3Enabled) {
            this.apiUrl = connections.resolve(ModlHttpClientV3Impl.BASE_PATH);
            this.httpClient = new ModlHttpClientV3Impl(connections, debugHttp);
        } else {
            this.apiUrl = connections.resolve(ModlHttpClientV2Impl.BASE_PATH);
            this.httpClient = new ModlHttpClientV2Impl(connections, debugHttp);
        }

        if (debugHttp) {
            logger.info("Base URL: " + apiUrl);
            logger.info("Proto V3 client: " + protoV3Enabled);
        }
    }

    public void shutdown() {
        httpClient.shutdown();
    }

    private static boolean isProtoV3Enabled() {
        String property = System.getProperty(PROTO_V3_PROPERTY);
        if (property != null) return Boolean.parseBoolean(property);
        String env = System.getenv(PROTO_V3_ENV);
        if (env != null) return Boolean.parseBoolean(env);
        return true;
    }

    private static boolean isProtoRuntimeCompatible() {
        try {
            return ApiError.getDefaultInstance().isInitialized();
        } catch (Throwable t) {
            logger.warning("Proto V3 runtime is incompatible with this server's bundled protobuf; using the V2 JSON HTTP client instead: " + t);
            return false;
        }
    }
}
