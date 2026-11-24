package gg.modl.backend.hostedimport.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import gg.modl.backend.infrastructure.disk.DiskSpaceFloor;
import gg.modl.backend.infrastructure.net.PublicAddressResolver;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportFilesAndDownloadTest {
    private static final String ID = "aaaaaaaa-0a53-4b8e-9d1c-0c8f0f0d2a11";
    private static final URI LINK = URI.create("https://bucket.s3.us-east-005.backblazeb2.com/a.zip?X-Amz-Signature=abc");

    @TempDir
    Path directory;

    @Test
    void archiveFilesAreReadableOnlyByTheOwner() throws Exception {
        ImportArchiveFiles files = new ImportArchiveFiles(directory.resolve("imports"), DiskSpaceFloor.DEFAULT);
        files.create(ID);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(files.pathOf(ID)))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("imports")))).isEqualTo("rwx------");
        }
    }

    @Test
    void chunksAppendAtTheCommittedOffsetAndOverwriteAnUncommittedTail() {
        ImportArchiveFiles files = new ImportArchiveFiles(directory.resolve("imports"), new DiskSpaceFloor(0, 0));
        files.create(ID);
        files.append(ID, 0, stream("abc"), 3);
        files.append(ID, 2, stream("XY"), 2);
        assertThat(files.size(ID)).isEqualTo(4);
        assertThatThrownBy(() -> files.append(ID, 10, stream("z"), 1)).isInstanceOf(ImportFailure.class);
    }

    @Test
    void theDownloadStopsBeforeWritingWhenTheDiskFloorWouldBeCrossed() {
        ImportArchiveFiles files = new ImportArchiveFiles(directory.resolve("imports"), new DiskSpaceFloor(Long.MAX_VALUE / 2, 0));
        LinkDownloader downloader = new LinkDownloader(
            (uri, from, etag) -> new ArchiveTransport.Response(200, OptionalLong.of(5), Optional.empty(), stream("12345")), files);
        assertReason(() -> downloader.download(ID, LINK, () -> false, (done, total) -> { }), ImportReason.DISK_SPACE);
    }

    @Test
    void redirectsAndExpiredLinksAreReportedByCode() {
        ImportArchiveFiles files = new ImportArchiveFiles(directory.resolve("imports"), new DiskSpaceFloor(0, 0));
        LinkDownloader redirected = new LinkDownloader(
            (uri, from, etag) -> new ArchiveTransport.Response(302, OptionalLong.empty(), Optional.empty(), stream("")), files);
        LinkDownloader expired = new LinkDownloader(
            (uri, from, etag) -> new ArchiveTransport.Response(403, OptionalLong.empty(), Optional.empty(), stream("")), files);
        assertReason(() -> redirected.download(ID, LINK, () -> false, (done, total) -> { }), ImportReason.LINK_REDIRECTED);
        assertReason(() -> expired.download(ID, LINK, () -> false, (done, total) -> { }), ImportReason.LINK_EXPIRED);
    }

    @Test
    void aCompleteDownloadIsStored() {
        ImportArchiveFiles files = new ImportArchiveFiles(directory.resolve("imports"), new DiskSpaceFloor(0, 0));
        LinkDownloader downloader = new LinkDownloader(
            (uri, from, etag) -> new ArchiveTransport.Response(200, OptionalLong.of(5), Optional.of("\"e\""), stream("12345")), files);
        assertThat(downloader.download(ID, LINK, () -> false, (done, total) -> { })).isEqualTo(5);
        assertThat(files.size(ID)).isEqualTo(5);
    }

    @Test
    void theLinkPolicyAcceptsOnlySignedHttpsBackblazeLinks() {
        LinkPolicy policy = new LinkPolicy();
        String good = "https://modl-exports.s3.us-east-005.backblazeb2.com/a.zip?X-Amz-Signature=abc";

        assertThat(policy.check(good)).isPresent();
        assertThat(policy.check("http://modl-exports.s3.us-east-005.backblazeb2.com/a.zip?X-Amz-Signature=abc")).isEmpty();
        assertThat(policy.check("https://modl-exports.s3.us-east-005.backblazeb2.com.evil.com/a.zip?X-Amz-Signature=abc")).isEmpty();
        assertThat(policy.check("https://user@modl-exports.s3.us-east-005.backblazeb2.com/a.zip?X-Amz-Signature=abc")).isEmpty();
        assertThat(policy.check("https://modl-exports.s3.us-east-005.backblazeb2.com:8443/a.zip?X-Amz-Signature=abc")).isEmpty();
        assertThat(policy.check("https://modl-exports.s3.us-east-005.backblazeb2.com/a.zip")).isEmpty();
        assertThat(policy.check("https://MODL-exports.s3.us-east-005.backblazeb2.com/a.zip?X-Amz-Signature=abc")).isEmpty();
    }

    @Test
    void theDownloadChecksTheAddressItConnectsToNotOnlyTheOneSeenWhenTheLinkWasPasted() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        PublicAddressResolver rebinding = new PublicAddressResolver(host -> {
            lookups.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("10.0.0.5")};
        });

        try (HttpArchiveTransport transport = new HttpArchiveTransport(rebinding)) {
            assertThatThrownBy(() -> transport.get(LINK, 0, null)).isInstanceOf(PublicAddressResolver.NonPublicAddressException.class);
            assertThatThrownBy(() -> transport.get(LINK, 0, null)).isInstanceOf(PublicAddressResolver.NonPublicAddressException.class);
        }
        assertThat(lookups.get()).isEqualTo(2);
    }

    private static void assertReason(Runnable action, ImportReason reason) {
        assertThatThrownBy(action::run).isInstanceOf(ImportFailure.class)
            .extracting(failure -> ((ImportFailure) failure).reason()).isEqualTo(reason);
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
