package gg.modl.backend.hostedimport.archive;

import gg.modl.backend.hostedimport.model.ImportFailure;
import gg.modl.backend.hostedimport.model.ImportReason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class CentralDirectoryCheck {
    private static final int END_RECORD_SIGNATURE = 0x06054b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int ZIP64_END_RECORD_SIGNATURE = 0x06064b50;
    private static final int END_RECORD_SIZE = 22;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_END_RECORD_SIZE = 56;
    private static final int MAX_COMMENT = 0xffff;
    private static final int UNSIGNED_SHORT_MAX = 0xffff;
    private static final long UNSIGNED_INT_MAX = 0xffffffffL;

    private CentralDirectoryCheck() {
    }

    public static CentralDirectory read(Path archive) {
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < END_RECORD_SIZE) {
                throw new ImportFailure(ImportReason.ARCHIVE_NOT_ZIP);
            }
            if (size > ArchiveLimits.MAX_ARCHIVE_BYTES) {
                throw new ImportFailure(ImportReason.ARCHIVE_TOO_LARGE);
            }
            long endOffset = findEndRecord(channel, size);
            ByteBuffer end = readAt(channel, endOffset, END_RECORD_SIZE);
            long entries = Short.toUnsignedInt(end.getShort(10));
            long directorySize = Integer.toUnsignedLong(end.getInt(12));
            long directoryOffset = Integer.toUnsignedLong(end.getInt(16));
            if (entries == UNSIGNED_SHORT_MAX || directorySize == UNSIGNED_INT_MAX || directoryOffset == UNSIGNED_INT_MAX) {
                CentralDirectory zip64 = readZip64(channel, endOffset);
                entries = zip64.entries();
                directorySize = zip64.directorySize();
                directoryOffset = zip64.directoryOffset();
            }
            return validated(new CentralDirectory(entries, directorySize, directoryOffset), size);
        } catch (IOException e) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED, e);
        }
    }

    private static CentralDirectory validated(CentralDirectory directory, long fileSize) {
        if (directory.entries() > ArchiveLimits.MAX_ENTRIES) {
            throw new ImportFailure(ImportReason.ARCHIVE_TOO_MANY_FILES);
        }
        if (directory.directorySize() > ArchiveLimits.MAX_CENTRAL_DIRECTORY_BYTES) {
            throw new ImportFailure(ImportReason.ARCHIVE_TOO_MANY_FILES);
        }
        if (directory.entries() < 0 || directory.directoryOffset() < 0 || directory.directorySize() < 0
            || directory.directoryOffset() > fileSize - directory.directorySize()) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
        }
        return directory;
    }

    private static long findEndRecord(FileChannel channel, long size) throws IOException {
        int window = (int) Math.min(size, END_RECORD_SIZE + MAX_COMMENT);
        ByteBuffer tail = readAt(channel, size - window, window);
        for (int position = window - END_RECORD_SIZE; position >= 0; position--) {
            if (tail.getInt(position) == END_RECORD_SIGNATURE) {
                return size - window + position;
            }
        }
        throw new ImportFailure(ImportReason.ARCHIVE_NOT_ZIP);
    }

    private static CentralDirectory readZip64(FileChannel channel, long endOffset) throws IOException {
        long locatorOffset = endOffset - ZIP64_LOCATOR_SIZE;
        if (locatorOffset < 0) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
        }
        ByteBuffer locator = readAt(channel, locatorOffset, ZIP64_LOCATOR_SIZE);
        if (locator.getInt(0) != ZIP64_LOCATOR_SIGNATURE) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
        }
        long recordOffset = locator.getLong(8);
        if (recordOffset < 0 || recordOffset + ZIP64_END_RECORD_SIZE > locatorOffset) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
        }
        ByteBuffer record = readAt(channel, recordOffset, ZIP64_END_RECORD_SIZE);
        if (record.getInt(0) != ZIP64_END_RECORD_SIGNATURE) {
            throw new ImportFailure(ImportReason.ARCHIVE_DAMAGED);
        }
        return new CentralDirectory(record.getLong(32), record.getLong(40), record.getLong(48));
    }

    private static ByteBuffer readAt(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new ImportFailure(ImportReason.ARCHIVE_INCOMPLETE);
            }
        }
        return buffer.flip();
    }

    public record CentralDirectory(long entries, long directorySize, long directoryOffset) {
    }
}
