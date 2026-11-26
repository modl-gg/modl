package gg.modl.backend.tunnel.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.backend.update.check.ReleaseHttpClient;
import gg.modl.update.ArtifactFiles;
import gg.modl.update.ReleaseVersion;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CloudflaredInstallerTest {
    private static final byte[] BINARY = "#!/bin/sh\necho cloudflared\n".getBytes(StandardCharsets.UTF_8);
    private static final HostPlatform LINUX = new HostPlatform("linux", "amd64");

    @TempDir
    Path data;

    private HttpServer github;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile byte[] served = BINARY;
    private TunnelDirectory directory;
    private ReleaseHttpClient http;

    @BeforeEach
    void setUp() throws IOException {
        github = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        github.createContext("/cloudflare/cloudflared/releases/download/", exchange -> {
            requests.incrementAndGet();
            byte[] body = served;
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        github.start();
        directory = TunnelDirectory.in(data);
        UpdateProperties properties = new UpdateProperties(null, null, null, base(), null);
        http = new ReleaseHttpClient(properties, ReleaseBuild.of(ReleaseVersion.parse("3.0.0"), ReleaseVersion.parse("3.0.0")),
            Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        github.stop(0);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aMissingBinaryIsDownloadedVerifiedAndMadeExecutable() throws Exception {
        Path binary = installer(build(BINARY)).verifiedBinary(() -> false);

        assertThat(binary).isEqualTo(data.resolve("tunnel/cloudflared-linux-amd64").toAbsolutePath().normalize());
        assertThat(Files.readAllBytes(binary)).isEqualTo(BINARY);
        assertThat(Files.isExecutable(binary)).isTrue();
        assertThat(requests).hasValue(1);
        assertThat(partials()).isEmpty();
    }

    @Test
    void anInstalledBinaryIsVerifiedOnEveryStartWithoutDownloadingAgain() throws Exception {
        CloudflaredInstaller installer = installer(build(BINARY));
        installer.verifiedBinary(() -> false);
        installer.verifiedBinary(() -> false);

        assertThat(requests).hasValue(1);
    }

    @Test
    void aDroppedInBinaryWithThePinnedHashIsUsedWithoutGithub() throws Exception {
        Files.createDirectories(directory.root());
        Files.write(directory.root().resolve("cloudflared-linux-amd64"), BINARY);
        github.stop(0);

        Path binary = installer(build(BINARY)).verifiedBinary(() -> false);

        assertThat(Files.readAllBytes(binary)).isEqualTo(BINARY);
        assertThat(requests).hasValue(0);
    }

    @Test
    void aTamperedBinaryIsReplacedByTheVerifiedDownload() throws Exception {
        Files.createDirectories(directory.root());
        byte[] tampered = "#!/bin/sh\necho evil!!!!!!!\n".getBytes(StandardCharsets.UTF_8);
        assertThat(tampered).hasSameSizeAs(BINARY);
        Files.write(directory.root().resolve("cloudflared-linux-amd64"), tampered);

        Path binary = installer(build(BINARY)).verifiedBinary(() -> false);

        assertThat(Files.readAllBytes(binary)).isEqualTo(BINARY);
        assertThat(requests).hasValue(1);
    }

    @Test
    void aDownloadThatDoesNotMatchThePinIsRefusedAndLeavesNothingBehind() throws IOException {
        served = "#!/bin/sh\necho other!!!!!!\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> installer(build(BINARY)).verifiedBinary(() -> false))
            .isInstanceOf(TunnelUnavailableException.class).hasMessageContaining("does not match the SHA-256");
        assertThat(directory.root().resolve("cloudflared-linux-amd64")).doesNotExist();
        assertThat(partials()).isEmpty();
    }

    @Test
    void aDownloadLargerThanThePinIsCutOff() {
        served = (new String(BINARY, StandardCharsets.UTF_8) + "padding").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> installer(build(BINARY)).verifiedBinary(() -> false))
            .isInstanceOf(TunnelUnavailableException.class).hasMessageContaining("could not be downloaded")
            .hasMessageContaining("put cloudflared-linux-amd64");
    }

    @Test
    void anUnreachableDownloadNamesTheDropInFolder() {
        github.stop(0);

        assertThatThrownBy(() -> installer(build(BINARY)).verifiedBinary(() -> false))
            .isInstanceOf(TunnelUnavailableException.class).hasMessageContaining(directory.root().toString());
    }

    @Test
    void filesOfOtherVersionsAreRemoved() throws Exception {
        Files.createDirectories(directory.root().resolve("2025.1.0"));
        Files.writeString(directory.root().resolve("cloudflared-linux-arm64"), "old");
        Files.writeString(directory.root().resolve("cloudflared-linux-amd64.123.partial"), "half");

        installer(build(BINARY)).verifiedBinary(() -> false);

        try (var entries = Files.list(directory.root())) {
            assertThat(entries.map(path -> path.getFileName().toString())).containsExactly("cloudflared-linux-amd64");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aDroppedInBinaryIsRestrictedToItsOwner() throws Exception {
        Files.createDirectories(directory.root());
        Path dropped = directory.root().resolve("cloudflared-linux-amd64");
        Files.write(dropped, BINARY);
        Files.setPosixFilePermissions(dropped, PosixFilePermissions.fromString("rwxrwxrwx"));

        installer(build(BINARY)).verifiedBinary(() -> false);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dropped))).isEqualTo("rwx------");
    }

    private List<String> partials() throws IOException {
        try (var entries = Files.list(directory.root())) {
            return entries.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".partial")).toList();
        }
    }

    private CloudflaredInstaller installer(CloudflaredBuild build) {
        return new CloudflaredInstaller(directory, build,
            (source, target, size, progress) -> http.download(source, target, size, Duration.ofSeconds(30), progress));
    }

    private CloudflaredBuild build(byte[] content) throws Exception {
        String sha256 = ArtifactFiles.hex(MessageDigest.getInstance("SHA-256").digest(content));
        return new CloudflaredBuild("2026.10.7", LINUX, content.length, sha256,
            URI.create(base() + "/cloudflare/cloudflared/releases/download/2026.10.7/cloudflared-linux-amd64"));
    }

    private String base() {
        return "http://127.0.0.1:" + github.getAddress().getPort();
    }
}
