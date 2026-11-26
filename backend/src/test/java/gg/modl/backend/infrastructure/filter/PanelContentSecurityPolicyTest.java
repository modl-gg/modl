package gg.modl.backend.infrastructure.filter;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.s3.S3Origins;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PanelContentSecurityPolicyTest {
    @Test
    void thePanelRunsOnlyItsOwnScriptsAndCannotBeFramedOrRebased() {
        Map<String, String> policy = directives(Set.of());

        assertThat(policy.get("default-src")).isEqualTo("'self'");
        assertThat(policy.get("script-src")).isEqualTo("'self'");
        assertThat(policy.get("connect-src")).isEqualTo(
            "'self' https://piston-meta.mojang.com https://piston-data.mojang.com https://textures.minecraft.net");
        assertThat(policy.get("object-src")).isEqualTo("'none'");
        assertThat(policy.get("base-uri")).isEqualTo("'none'");
        assertThat(policy.get("frame-ancestors")).isEqualTo("'none'");
        assertThat(String.join(" ", policy.values())).doesNotContain("unsafe-eval").doesNotContain("*");
    }

    @Test
    void anS3BucketIsReachableForUploadsAndMedia() {
        StorageProperties.S3 s3 = new StorageProperties.S3("https://s3.eu.example.com/", "eu", "bucket", "key", "secret",
            "https://cdn.example.com/media");
        Map<String, String> policy = directives(new LinkedHashSet<>(S3Origins.of(s3)));

        assertThat(policy.get("connect-src")).contains("https://s3.eu.example.com", "https://cdn.example.com");
        assertThat(policy.get("img-src")).contains("https://cdn.example.com");
        assertThat(policy.get("media-src")).contains("https://cdn.example.com");
    }

    @Test
    void theHeaderFollowsAStorageSwitchWithoutARestart() {
        AtomicReference<Set<String>> origins = new AtomicReference<>(Set.of());
        PanelContentSecurityPolicyWriter writer = new PanelContentSecurityPolicyWriter(origins::get);

        MockHttpServletResponse before = write(writer);
        origins.set(Set.of("https://cdn.example.com"));
        MockHttpServletResponse after = write(writer);

        assertThat(before.getHeader(PanelContentSecurityPolicyWriter.HEADER)).doesNotContain("https://cdn.example.com");
        assertThat(after.getHeader(PanelContentSecurityPolicyWriter.HEADER)).contains("https://cdn.example.com");
    }

    @Test
    void aStricterPolicySetByTheHandlerIsKept() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(PanelContentSecurityPolicyWriter.HEADER, "sandbox; default-src 'none'");

        new PanelContentSecurityPolicyWriter(Set::of).writeHeaders(new MockHttpServletRequest(), response);

        assertThat(response.getHeader(PanelContentSecurityPolicyWriter.HEADER)).isEqualTo("sandbox; default-src 'none'");
    }

    private static MockHttpServletResponse write(PanelContentSecurityPolicyWriter writer) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.writeHeaders(new MockHttpServletRequest(), response);
        return response;
    }

    private static Map<String, String> directives(Set<String> origins) {
        return Arrays.stream(PanelContentSecurityPolicy.of(origins).split("; "))
            .map(directive -> directive.split(" ", 2))
            .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
    }
}
