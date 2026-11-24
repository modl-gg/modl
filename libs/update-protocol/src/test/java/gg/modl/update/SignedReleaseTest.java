package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.update.ReleaseVerificationException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignedReleaseTest {
    private static final GsonJson JSON = new GsonJson();

    @Test
    void verifiesAnEnvelopeSignedByOpenssl() throws Exception {
        ReleaseKeys keys = ReleaseKeys.parse(resource("openssl-release-keys.txt"));
        byte[] envelope = resource("openssl-release.json").getBytes(StandardCharsets.UTF_8);

        SignedRelease release = SignedRelease.verify(envelope, keys, JSON);

        assertEquals(ReleaseVersion.parse("3.0.1"), release.version());
        assertEquals("modl-server-3.0.1.jar", release.manifest().artifact(ArtifactKind.SERVER).file());
        assertEquals(3000, release.manifest().artifact(ArtifactKind.SERVER).size());
        assertEquals(ReleaseVersion.parse("3.0.0"), release.manifest().minUpgradeFrom());
        assertArrayEquals(envelope, release.envelope());
    }

    @Test
    void rejectsATamperedPayload() throws Exception {
        TestReleases releases = new TestReleases();
        byte[] payload = new GsonJson().write(TestReleases.manifest("3.0.1"));
        byte[] signature = releases.sign(payload);
        byte[] tampered = Arrays.copyOf(payload, payload.length);
        tampered[tampered.length - 3] ^= 1;

        assertReason(Reason.INVALID_SIGNATURE, TestReleases.envelope(tampered, signature, releases.keyId(), 1), releases.keys());
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        TestReleases signer = new TestReleases();
        TestReleases other = new TestReleases();

        assertReason(Reason.INVALID_SIGNATURE, signer.envelope(TestReleases.manifest("3.0.1")), other.keys());
    }

    @Test
    void reportsNewerFormatsAsUnsupported() throws Exception {
        TestReleases releases = new TestReleases();
        byte[] payload = JSON.write(TestReleases.manifest("3.0.1"));
        assertReason(Reason.UNSUPPORTED_FORMAT, TestReleases.envelope(payload, releases.sign(payload), releases.keyId(), 2),
            releases.keys());

        Map<String, Object> newerManifest = TestReleases.manifest("3.0.1");
        newerManifest.put("format", 2);
        assertReason(Reason.UNSUPPORTED_FORMAT, releases.envelope(newerManifest), releases.keys());
    }

    @Test
    void rejectsOversizeEnvelopes() throws Exception {
        TestReleases releases = new TestReleases();
        byte[] oversize = new byte[SignedRelease.MAX_ENVELOPE_BYTES + 1];
        Arrays.fill(oversize, (byte) ' ');

        assertReason(Reason.INVALID_MANIFEST, oversize, releases.keys());
    }

    @Test
    void rejectsSignedButInvalidManifests() throws Exception {
        TestReleases releases = new TestReleases();
        Map<String, Object> wrongChannel = TestReleases.manifest("3.0.1-rc.1");
        wrongChannel.put("channel", "stable");
        assertReason(Reason.INVALID_MANIFEST, releases.envelope(wrongChannel), releases.keys());

        Map<String, Object> wrongProduct = TestReleases.manifest("3.0.1");
        wrongProduct.put("product", "other");
        assertReason(Reason.INVALID_MANIFEST, releases.envelope(wrongProduct), releases.keys());

        Map<String, Object> renamedArtifact = TestReleases.manifest("3.0.1");
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) ((Map<String, Object>) renamedArtifact.get("artifacts")).get("server");
        server.put("file", "../modl-server-3.0.1.jar");
        assertReason(Reason.INVALID_MANIFEST, releases.envelope(renamedArtifact), releases.keys());
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        TestReleases releases = new TestReleases();
        Map<String, Object> manifest = TestReleases.manifest("3.0.2");
        manifest.put("notes", "added later");

        assertEquals(ReleaseVersion.parse("3.0.2"), SignedRelease.verify(releases.envelope(manifest), releases.keys(), JSON).version());
    }

    @Test
    void limitsTheBundledKeysToTwo() throws Exception {
        String three = String.join("\n", firstLine(new TestReleases()), firstLine(new TestReleases()), firstLine(new TestReleases()));
        assertThrows(IllegalStateException.class, () -> ReleaseKeys.parse(three));
        assertTrue(ReleaseKeys.parse("# only a comment\n").isEmpty());
    }

    private static String firstLine(TestReleases releases) {
        ReleaseKeys keys = releases.keys();
        return Base64.getEncoder().encodeToString(keys.publicKey(keys.sortedKeyIds().get(0)));
    }

    private static void assertReason(Reason expected, byte[] envelope, ReleaseKeys keys) {
        ReleaseVerificationException failure = assertThrows(ReleaseVerificationException.class,
            () -> SignedRelease.verify(envelope, keys, JSON));
        assertEquals(expected, failure.reason(), failure.getMessage());
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = SignedReleaseTest.class.getResourceAsStream(name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
