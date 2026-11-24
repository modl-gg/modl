package gg.modl.backend.hostedimport.archive;

import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import org.jspecify.annotations.Nullable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipException;

public final class VerifiedEntryStream extends FilterInputStream {
    private final long declaredSize;
    @Nullable
    private final String expectedSha256;
    private final MessageDigest digest;
    private long read;
    private boolean verified;

    VerifiedEntryStream(InputStream entry, long declaredSize, @Nullable String expectedSha256) {
        super(entry);
        this.declaredSize = declaredSize;
        this.expectedSha256 = expectedSha256;
        this.digest = sha256();
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int count = read(single, 0, 1);
        return count < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count;
        try {
            count = super.read(buffer, offset, length);
        } catch (ZipException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
        if (count < 0) {
            verifyAtEnd();
            return -1;
        }
        read += count;
        if (read > declaredSize) {
            throw new ImportFailure(ImportReason.ARCHIVE_CHECKSUM);
        }
        digest.update(buffer, offset, count);
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        byte[] discard = new byte[(int) Math.min(count, 8192)];
        long skipped = 0;
        while (skipped < count) {
            int chunk = read(discard, 0, (int) Math.min(discard.length, count - skipped));
            if (chunk < 0) {
                break;
            }
            skipped += chunk;
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    public long bytesRead() {
        return read;
    }

    public void drainAndVerify() throws IOException {
        byte[] buffer = new byte[65536];
        int count;
        do {
            count = read(buffer, 0, buffer.length);
        } while (count >= 0);
    }

    public RuntimeException integrityFailureOr(RuntimeException unexpected) {
        try {
            drainAndVerify();
        } catch (ImportFailure damaged) {
            damaged.addSuppressed(unexpected);
            return damaged;
        } catch (IOException | UncheckedIOException damaged) {
            ImportFailure failure = new ImportFailure(ImportReason.ARCHIVE_DAMAGED, damaged);
            failure.addSuppressed(unexpected);
            return failure;
        }
        return unexpected;
    }

    private void verifyAtEnd() {
        if (verified) {
            return;
        }
        verified = true;
        if (read != declaredSize) {
            throw new ImportFailure(ImportReason.ARCHIVE_CHECKSUM);
        }
        if (expectedSha256 != null && !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
            throw new ImportFailure(ImportReason.ARCHIVE_CHECKSUM);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
