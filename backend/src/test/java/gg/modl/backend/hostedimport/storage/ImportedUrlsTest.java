package gg.modl.backend.hostedimport.storage;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.hostedimport.HostedArchiveFixture;
import gg.modl.backend.hostedimport.ImportTestContexts;
import gg.modl.backend.hostedimport.mapping.ArchiveIndex;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.IssueLog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportedUrlsTest {
    private static final String KEY = HostedArchiveFixture.PREFIX + "evidence/a";
    private static final String DOTTED = HostedArchiveFixture.PREFIX + "evidence/b.png";

    @TempDir
    Path directory;

    private IssueLog issues;
    private ImportedUrls urls;

    @BeforeEach
    void setUp() throws IOException {
        ArchiveIndex index = ImportTestContexts.index(new HostedArchiveFixture().object("evidence/a", "x").object("evidence/b.png", "y"),
            directory);
        issues = new IssueLog();
        urls = new ImportedUrls(index.manifest(), index.keyPlan(), ImportTestContexts.storage(), issues);
    }

    @Test
    void anExportedKeyBehindAPublicBaseIsRewrittenWithTrailingPunctuationKept() {
        assertThat(urls.rewriteText("see " + HostedArchiveFixture.BASE_URL + DOTTED + ". thanks"))
            .isEqualTo("see /v1/objects/modl_instance/evidence/b.png. thanks");
        assertThat(urls.rewriteText("(" + HostedArchiveFixture.BASE_URL + DOTTED + "?v=2)"))
            .isEqualTo("(/v1/objects/modl_instance/evidence/b.png)");
    }

    @Test
    void aMissingFileIsNeverResolvedToAnotherFileThatSharesItsPrefix() {
        String missing = HostedArchiveFixture.BASE_URL + KEY + ".png";
        assertThat(urls.rewriteText(missing)).isEqualTo(missing);
        assertThat(issues.tally().issues()).containsKey(IssueCode.URL_LEFT_REMOTE);
    }

    @Test
    void anOtherHostUrlIsRewrittenOnlyForAnExactExportedKey() {
        assertThat(urls.rewriteText("https://old-bucket.example.com/" + DOTTED)).isEqualTo("/v1/objects/modl_instance/evidence/b.png");
        String near = "https://old-bucket.example.com/" + DOTTED + "x";
        assertThat(urls.rewriteText(near)).isEqualTo(near);
    }

    @Test
    void oldPanelLinksBecomeRelativeAndOtherLinksStay() {
        assertThat(urls.rewriteText("https://example.modl.gg/replay?id=r1")).isEqualTo("/replay?id=r1");
        assertThat(urls.rewriteText("https://example.com/page")).isEqualTo("https://example.com/page");
    }

    @Test
    void rawKeyMembersAreMappedAndUnsafeSchemesNeutralisedInUntrustedMaps() {
        Map<String, Object> rewritten = urls.rewriteUntrustedMap(Map.of("key", DOTTED, "link", "javascript:alert(1)",
            "nested", List.of(Map.of("key", "unknown"))));
        assertThat(rewritten).containsEntry("key", "modl_instance/evidence/b.png").containsEntry("link", "");
        assertThat(rewritten.get("nested")).isEqualTo(List.of(Map.of("key", "unknown")));
    }

    @Test
    void safeUrlRefusesScriptUrls() {
        assertThat(urls.safeUrl("javascript:alert(1)")).isNull();
        assertThat(urls.safeUrl(HostedArchiveFixture.BASE_URL + KEY)).isEqualTo("/v1/objects/modl_instance/evidence/a");
    }
}
