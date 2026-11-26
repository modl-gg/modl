package gg.modl.backend.tunnel.cloudflared;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public record CloudflaredRelease(String version, List<CloudflaredBuild> builds) {
    static final String RESOURCE = "/gg/modl/backend/tunnel/cloudflared/cloudflared-release.txt";
    static final String DOWNLOAD_BASE = "https://github.com/cloudflare/cloudflared/releases/download/";

    private static final String HEADER = "cloudflared ";
    private static final Pattern VERSION = Pattern.compile("\\d{4}\\.\\d{1,2}\\.\\d{1,3}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_SIZE = 256L * 1024 * 1024;
    private static final String WINDOWS_SUFFIX = ".exe";

    public CloudflaredRelease {
        builds = List.copyOf(builds);
    }

    public static CloudflaredRelease pinned() {
        try (InputStream input = CloudflaredRelease.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("The pinned cloudflared release " + RESOURCE + " is missing");
            }
            return parse(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CloudflaredRelease parse(List<String> lines) {
        if (lines.isEmpty() || !lines.getFirst().startsWith(HEADER)) {
            throw new IllegalStateException("The pinned cloudflared release does not start with '" + HEADER + "<version>'");
        }
        String version = lines.getFirst().substring(HEADER.length()).trim();
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalStateException("The pinned cloudflared version '" + version + "' is malformed");
        }
        List<CloudflaredBuild> builds = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                builds.add(build(version, line.trim()));
            }
        }
        return new CloudflaredRelease(version, builds);
    }

    public Optional<CloudflaredBuild> forPlatform(HostPlatform platform) {
        return builds.stream().filter(build -> build.platform().equals(platform)).findFirst();
    }

    private static CloudflaredBuild build(String version, String line) {
        String[] fields = line.split(" +");
        if (fields.length != 4 || !SHA256.matcher(fields[3]).matches()) {
            throw new IllegalStateException("The pinned cloudflared build '" + line + "' is not '<os> <arch> <size> <sha256>'");
        }
        long size = Long.parseLong(fields[2]);
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalStateException("The pinned cloudflared build '" + line + "' has an invalid size");
        }
        HostPlatform platform = new HostPlatform(fields[0], fields[1]);
        String file = "cloudflared-" + platform.os() + "-" + platform.arch() + (platform.isWindows() ? WINDOWS_SUFFIX : "");
        return new CloudflaredBuild(version, platform, size, fields[3], URI.create(DOWNLOAD_BASE + version + "/" + file));
    }
}
