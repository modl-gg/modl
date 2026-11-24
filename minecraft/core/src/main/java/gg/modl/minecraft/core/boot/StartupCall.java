package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.response.StartupResponse;

import java.io.IOException;

interface StartupCall {
    String serverUrl();

    StartupResponse call() throws IOException;
}
