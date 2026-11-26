package gg.modl.backend.tunnel.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CloudflaredReleaseTest {

    @Test
    void thePinnedReleaseCoversLinuxOnBothArchitecturesAndWindows() {
        CloudflaredRelease release = CloudflaredRelease.pinned();

        assertThat(release.forPlatform(new HostPlatform("linux", "amd64"))).hasValueSatisfying(build -> {
            assertThat(build.file()).isEqualTo("cloudflared-linux-amd64");
            assertThat(build.source().toString()).isEqualTo(
                "https://github.com/cloudflare/cloudflared/releases/download/" + release.version() + "/cloudflared-linux-amd64");
            assertThat(build.sha256()).hasSize(64);
        });
        assertThat(release.forPlatform(new HostPlatform("linux", "arm64"))).isPresent();
        assertThat(release.forPlatform(new HostPlatform("windows", "amd64")))
            .hasValueSatisfying(build -> assertThat(build.file()).isEqualTo("cloudflared-windows-amd64.exe"));
        assertThat(release.forPlatform(new HostPlatform("linux", "riscv64"))).isEmpty();
    }

    @Test
    void javaPlatformNamesMapToCloudflaredNames() {
        assertThat(HostPlatform.of("Linux", "amd64")).isEqualTo(new HostPlatform("linux", "amd64"));
        assertThat(HostPlatform.of("Linux", "aarch64")).isEqualTo(new HostPlatform("linux", "arm64"));
        assertThat(HostPlatform.of("Windows 11", "amd64")).isEqualTo(new HostPlatform("windows", "amd64"));
        assertThat(HostPlatform.of("Mac OS X", "aarch64")).isEqualTo(new HostPlatform("darwin", "arm64"));
    }

    @Test
    void aMalformedPinIsRefused() {
        assertThatThrownBy(() -> CloudflaredRelease.parse(List.of("cloudflared 2026.10.7", "linux amd64 12 not-a-hash")))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CloudflaredRelease.parse(List.of("cloudflared latest"))).isInstanceOf(IllegalStateException.class);
    }
}
