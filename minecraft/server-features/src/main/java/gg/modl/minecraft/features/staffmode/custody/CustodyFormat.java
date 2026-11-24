package gg.modl.minecraft.features.staffmode.custody;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

final class CustodyFormat {
    private static final String HEADER = "modl-staff-custody 1";
    private static final String HELD = "HELD";
    private static final String RELEASED = "RELEASED";
    private static final int HEADER_LINE = 0;
    private static final int ID_LINE = 1;
    private static final int STATE_LINE = 2;
    private static final int MARK_LINE = 3;
    private static final int GAME_MODE_LINE = 4;
    private static final int CHECKSUM_LINE = 5;
    private static final int BELONGINGS = 6;
    private static final int PART_COUNT = 7;

    private CustodyFormat() {
    }

    static byte[] write(CustodyRecord record) {
        String text = String.join("\n", HEADER, record.getId().toString(), record.isReleased() ? RELEASED : HELD,
                record.getMarkKind(), record.getGameMode(), checksum(record), record.getBelongings());
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static CustodyRecord read(byte[] content) throws IOException {
        String[] parts = decodeUtf8(content).split("\n", PART_COUNT);
        if (parts.length != PART_COUNT || !HEADER.equals(parts[HEADER_LINE])) {
            throw new IOException("it is not a \"" + HEADER + "\" record");
        }
        String state = parts[STATE_LINE];
        if (!HELD.equals(state) && !RELEASED.equals(state)) throw new IOException("its state is unknown");
        CustodyRecord record = new CustodyRecord(readId(parts[ID_LINE]), RELEASED.equals(state), parts[MARK_LINE],
                parts[GAME_MODE_LINE], parts[BELONGINGS]);
        if (!checksum(record).equals(parts[CHECKSUM_LINE])) throw new IOException("its checksum does not match");
        return record;
    }

    private static String decodeUtf8(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
    }

    private static UUID readId(String text) throws IOException {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("its id is malformed", malformed);
        }
    }

    private static String checksum(CustodyRecord record) {
        String protectedText = String.join("\n", record.getId().toString(), String.valueOf(record.isReleased()),
                record.getMarkKind(), record.getGameMode(), record.getBelongings());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(protectedText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }
}
